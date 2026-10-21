package dev.csmc.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import dev.csmc.CsmcMod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Server-side weapon definitions. Layers, lowest first: generated class placeholders, datapack
 * {@code data/<namespace>/weapons/**.json}, the user's local {@code ~/.csmc/cache/data/weapons.vdata}
 * ({@link Cs2WeaponImporter}), then authored balance overrides from {@code data/<namespace>/weapon_balance/}
 * ({@link WeaponBalance}). Rebuilt on startup and every datapack reload ({@code /reload}).
 *
 * <p>Bundled definitions are self-authored approximations. Invalid datapack definitions retain the
 * previous snapshot. An absent or unreadable local export keeps the datapack definitions; malformed
 * imported weapons are logged and individually retain their datapack fallback when available. Malformed
 * balance overrides are logged and ignored individually; they never reject the reload.
 */
public final class WeaponRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public static final String DIRECTORY = "weapons";
    public static final ResourceLocation LISTENER_ID = CsmcMod.id("weapons");

    private static volatile Map<ResourceLocation, WeaponData> weapons = Map.of();
    private static volatile List<String> lastErrors = List.of();
    private static volatile List<String> lastBalanceErrors = List.of();
    private static volatile Set<ResourceLocation> balanced = Set.of();
    private static boolean registered;

    private WeaponRegistry() {}

    /** Weapon definition for an id, or null if unknown. */
    public static WeaponData get(ResourceLocation id) {
        return weapons.get(id);
    }

    /** Immutable snapshot of all loaded weapons. */
    public static Map<ResourceLocation, WeaponData> all() {
        return weapons;
    }

    /** Errors from the most recent reload; empty when it succeeded. */
    public static List<String> lastErrors() {
        return lastErrors;
    }

    /** Rejected balance override files from the most recent installed reload; empty when all applied. */
    public static List<String> lastBalanceErrors() {
        return lastBalanceErrors;
    }

    /** Weapons whose balance override was applied by the most recent installed reload. */
    public static java.util.Set<ResourceLocation> balanced() {
        return balanced;
    }

    /** Registers the weapon and {@link MaterialTable} SERVER_DATA reload listeners. Idempotent. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new Listener());
        MaterialTable.register();
    }

    /**
     * Builds and installs every layer atomically.
     *
     * @return true if the registry was replaced; false if any datapack definition failed (previous registry kept)
     */
    static boolean apply(Map<ResourceLocation, JsonElement> files, Map<ResourceLocation, String> balanceFiles) {
        WeaponLayers.Build build = WeaponLayers.build(CsmcMod.FIREARM_IDS, files, balanceFiles,
                CachePaths.root().resolve("data/weapons.vdata"));
        if (build.weapons() == null) {
            lastErrors = build.errors();
            for (String error : build.errors()) {
                LOGGER.error("[csmc] Invalid weapon definition {}", error);
            }
            LOGGER.error("[csmc] Rejected weapon reload ({} invalid files); keeping previous {} weapons{}",
                    build.errors().size(), weapons.size(),
                    weapons.isEmpty() ? " — WEAPON REGISTRY IS EMPTY, guns will not fire" : "");
            return false;
        }
        weapons = build.weapons();
        lastErrors = List.of();
        lastBalanceErrors = build.balanceErrors();
        balanced = build.balanced();
        if (weapons.isEmpty()) {
            LOGGER.error("[csmc] No weapon definitions found under data/*/{}/ — weapon registry is empty", DIRECTORY);
        } else {
            LOGGER.info("[csmc] Loaded {} weapons ({} balance overrides): {}", weapons.size(), balanced.size(),
                    weapons.keySet());
        }
        return true;
    }

    private record Prepared(Map<ResourceLocation, JsonElement> weapons, Map<ResourceLocation, String> balance) {}

    private static final class Listener extends SimplePreparableReloadListener<Prepared>
            implements IdentifiableResourceReloadListener {
        @Override
        public ResourceLocation getFabricId() {
            return LISTENER_ID;
        }

        @Override
        protected Prepared prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, JsonElement> weapons = new HashMap<>();
            SimpleJsonResourceReloadListener.scanDirectory(manager, DIRECTORY, GSON, weapons);
            // Raw text: WeaponBalance parses strictly (vanilla scanning accepts trailing content).
            Map<ResourceLocation, String> balance = new HashMap<>();
            FileToIdConverter converter = FileToIdConverter.json(WeaponBalance.DIRECTORY);
            converter.listMatchingResources(manager).forEach((file, resource) -> {
                ResourceLocation id = converter.fileToId(file);
                try (var reader = resource.openAsReader()) {
                    balance.put(id, reader.lines().collect(java.util.stream.Collectors.joining("\n")));
                } catch (java.io.IOException | java.io.UncheckedIOException e) {
                    LOGGER.warn("[csmc] Cannot read weapon balance override {}: {}", file, e.getMessage());
                    balance.put(id, ""); // reported and ignored as malformed by WeaponBalance
                }
            });
            return new Prepared(weapons, balance);
        }

        @Override
        protected void apply(Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
            WeaponRegistry.apply(prepared.weapons(), prepared.balance());
        }
    }
}
