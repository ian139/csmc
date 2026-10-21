package dev.csmc.balance;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.csmc.data.WeaponBalance;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponLayers;
import dev.csmc.data.WeaponRole;
import dev.csmc.data.WeaponRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;

/** Builds the shipped weapon layers exactly as {@code WeaponRegistry} does, from the bundled datapack. */
final class BalanceData {
    static final Path DATA = Path.of("src/main/resources/data");
    /** The user's local CS2 export; present on development machines, absent in clean CI. */
    static final Path EXPORT = Path.of(System.getProperty("user.home"), ".csmc/cache/data/weapons.vdata");
    /** A path that never exists: builds the no-cache defaults. */
    static final Path NO_EXPORT = Path.of("build/no-such-weapons.vdata");

    private BalanceData() {}

    /** Every firearm with its final definition, in role order. */
    static Map<String, WeaponData> load(Path vdata) {
        Map<ResourceLocation, JsonElement> weapons = new LinkedHashMap<>();
        scan(WeaponRegistry.DIRECTORY).forEach((id, text) -> weapons.put(id, JsonParser.parseString(text)));
        WeaponLayers.Build build = WeaponLayers.build(WeaponRole.all().keySet(), weapons,
                scan(WeaponBalance.DIRECTORY), vdata);
        if (build.weapons() == null || !build.balanceErrors().isEmpty()) {
            throw new IllegalStateException("bundled weapon data invalid: " + build.errors() + build.balanceErrors());
        }
        Map<String, WeaponData> result = new LinkedHashMap<>();
        for (String id : WeaponRole.all().keySet()) {
            result.put(id, build.weapons().get(ResourceLocation.fromNamespaceAndPath("csmc", id)));
        }
        return result;
    }

    /** Raw text of every {@code data/<namespace>/<directory>/**.json} file. */
    static Map<ResourceLocation, String> scan(String directory) {
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        try (Stream<Path> namespaces = Files.list(DATA)) {
            for (Path namespace : namespaces.sorted().toList()) {
                Path root = namespace.resolve(directory);
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> paths = Files.walk(root)) {
                    for (Path file : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        String path = root.relativize(file).toString().replace('\\', '/');
                        path = path.substring(0, path.length() - ".json".length());
                        files.put(ResourceLocation.fromNamespaceAndPath(namespace.getFileName().toString(), path),
                                Files.readString(file));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}
