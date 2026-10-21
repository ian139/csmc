package dev.csmc.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure weapon layer build shared by {@link WeaponRegistry} and the balance harness. Layers, lowest first:
 * generated class placeholders, datapack weapon definitions, the local CS2 export, authored balance overrides.
 */
public final class WeaponLayers {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeaponLayers.class);

    private WeaponLayers() {}

    /**
     * One layer build. {@code weapons} is null, with one message per file in {@code errors}, when a datapack
     * weapon definition is invalid. {@code balanced} lists applied overrides; each ignored override has one
     * {@code "<id>: <reason>"} message in {@code balanceErrors}.
     */
    public record Build(@Nullable Map<ResourceLocation, WeaponData> weapons,
            Set<ResourceLocation> balanced, List<String> errors, List<String> balanceErrors) {}

    /** Weapon id in the mod namespace; mirrors {@code CsmcMod.id} without loading the mod class. */
    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("csmc", path);
    }

    /**
     * Builds every layer without touching the installed registry.
     *
     * @param firearmIds registered firearm paths that receive a generated placeholder
     * @param vdata the local CS2 export to import; a missing file skips the import layer
     */
    public static Build build(Collection<String> firearmIds, Map<ResourceLocation, JsonElement> files,
            Map<ResourceLocation, String> balanceFiles, Path vdata) {
        Map<ResourceLocation, JsonElement> definitions = new LinkedHashMap<>();
        for (String id : firearmIds) {
            definitions.put(id(id), WeaponPlaceholders.create(id));
        }
        definitions.putAll(files);
        Map<ResourceLocation, WeaponData> parsed = new LinkedHashMap<>();
        Map<ResourceLocation, JsonObject> sources = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> file : definitions.entrySet()) {
            try {
                parsed.put(file.getKey(), WeaponData.fromJson(file.getKey(), file.getValue()));
                sources.put(file.getKey(), file.getValue().getAsJsonObject());
            } catch (RuntimeException e) {
                errors.add(file.getKey() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            return new Build(null, Set.of(), List.copyOf(errors), List.of());
        }
        // Only real datapack files count as authored; generated placeholders are the importer's own base.
        importCachedWeapons(files, vdata, parsed, sources);
        List<String> balanceErrors = new ArrayList<>();
        Set<ResourceLocation> balanced = applyBalance(balanceFiles, parsed, sources, balanceErrors);
        return new Build(Map.copyOf(parsed), Set.copyOf(balanced), List.of(), List.copyOf(balanceErrors));
    }

    /** The last layer: exported vdata never overrides authored balance. Returns the applied ids. */
    private static Set<ResourceLocation> applyBalance(Map<ResourceLocation, String> balanceFiles,
            Map<ResourceLocation, WeaponData> parsed, Map<ResourceLocation, JsonObject> sources, List<String> errors) {
        Set<ResourceLocation> applied = new LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, JsonObject> entry : WeaponBalance.parse(balanceFiles, errors).entrySet()) {
            ResourceLocation id = entry.getKey();
            JsonObject source = sources.get(id);
            if (source == null) {
                errors.add(id + ": no weapon definition with this id");
                continue;
            }
            try {
                parsed.put(id, WeaponData.fromJson(id, WeaponBalance.apply(source, entry.getValue())));
                applied.add(id);
            } catch (RuntimeException e) {
                errors.add(id + ": " + e.getMessage());
            }
        }
        for (String error : errors) {
            LOGGER.warn("[csmc] Ignoring weapon balance override {}", error);
        }
        return applied;
    }

    private static void importCachedWeapons(Map<ResourceLocation, JsonElement> files, Path file,
            Map<ResourceLocation, WeaponData> parsed, Map<ResourceLocation, JsonObject> sources) {
        if (!Files.isRegularFile(file)) {
            LOGGER.info("[csmc] No local CS2 weapons export at {}; using self-authored datapack defaults", file);
            return;
        }
        Map<String, JsonObject> authored = new LinkedHashMap<>();
        files.forEach((id, json) -> {
            if (id.getNamespace().equals("csmc") && json.isJsonObject()) {
                authored.put(id.getPath(), json.getAsJsonObject());
            }
        });
        List<String> imported = new ArrayList<>();
        try {
            for (Map.Entry<String, JsonObject> entry : Cs2WeaponImporter.load(file, authored).entrySet()) {
                try {
                    ResourceLocation id = id(entry.getKey());
                    parsed.put(id, WeaponData.fromJson(id, entry.getValue()));
                    sources.put(id, entry.getValue());
                    imported.add(entry.getKey());
                } catch (RuntimeException e) {
                    LOGGER.warn("[csmc] Invalid imported weapon {}: {}; keeping datapack fallback if present",
                            entry.getKey(), e.getMessage());
                }
            }
            LOGGER.info("[csmc] Imported {} CS2 weapons from {}: {}", imported.size(), file, imported);
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("[csmc] Cannot import local CS2 weapons from {}; using datapack defaults: {}",
                    file, e.getMessage());
        }
    }
}
