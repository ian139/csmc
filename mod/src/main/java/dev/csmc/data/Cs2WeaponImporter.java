package dev.csmc.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.Nullable;

/** Converts locally exported CS2 KV3 to the mod's weapon JSON contract without writing copied data. */
public final class Cs2WeaponImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(Cs2WeaponImporter.class);
    private static final List<String> STANCES = List.of("stand", "crouch", "move", "jump", "land", "ladder", "fire");
    private record Supply(int reserve, double reloadSeconds) {}

    // Hand-authored game facts, not extracted files. Animation timing is not supplied by weapons.vdata.
    private static final Map<String, Supply> SUPPLIES = Map.ofEntries(
            Map.entry("ak47", new Supply(90, 2.43)),
            Map.entry("awp", new Supply(30, 3.67)),
            Map.entry("glock", new Supply(120, 2.27)),
            Map.entry("m4a1", new Supply(90, 3.07)),
            Map.entry("m4a1_silencer", new Supply(80, 3.07)),
            Map.entry("usp_silencer", new Supply(24, 2.17)),
            Map.entry("deagle", new Supply(35, 2.2)),
            Map.entry("nova", new Supply(32, 3.0)),
            Map.entry("xm1014", new Supply(32, 3.0)));

    private static final Map<String, String> DIRECT = Map.ofEntries(
            Map.entry("damage", "m_nDamage"),
            Map.entry("magazine_size", "m_iMaxClip1"),
            Map.entry("cycle_time_s", "m_flCycleTime"),
            Map.entry("range", "m_flRange"),
            Map.entry("range_modifier", "m_flRangeModifier"),
            Map.entry("penetration_power", "m_flPenetration"),
            Map.entry("armor_ratio", "m_flArmorRatio"),
            Map.entry("spread_base", "m_flSpread"),
            Map.entry("automatic", "m_bIsFullAuto"),
            Map.entry("headshot_multiplier", "m_flHeadshotMultiplier"),
            Map.entry("pellets", "m_nNumBullets"),
            Map.entry("max_speed", "m_flMaxSpeed"),
            Map.entry("deploy_time_s", "m_flDeployDuration"));

    private Cs2WeaponImporter() {}

    /** Reads a cache export and overlays it onto authored definitions; no Valve data is persisted. */
    public static Map<String, JsonObject> load(Path source, Map<String, JsonObject> authored) throws IOException {
        return importText(Files.readString(source), authored);
    }

    /**
     * Imports every gun of a KV3 export. Precedence, lowest first: the generated class placeholder
     * ({@link WeaponPlaceholders}, reserve rescaled to three exported magazines), the hand-authored
     * {@code SUPPLIES} (reserve, reload), the authored datapack definition in {@code authored} (keyed by id),
     * then the exported fields. Values the export does not carry ({@code reload_time_s}, {@code reserve_ammo})
     * therefore keep their authored fallback; an authored {@code ammo_type} wins over the type derived from
     * {@code m_WeaponType}. A gun that fails to convert or merge is logged and skipped, so it keeps its
     * authored fallback; the others still import.
     */
    public static Map<String, JsonObject> importText(String text, Map<String, JsonObject> authored) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : parse(text).entrySet()) {
            try {
                result.put(entry.getKey(), merge(entry.getKey(), entry.getValue(), authored.get(entry.getKey())));
            } catch (RuntimeException e) {
                LOGGER.warn("[csmc] Cannot import CS2 weapon {}: {}; retaining its authored fallback",
                        entry.getKey(), e.getMessage());
            }
        }
        return Map.copyOf(result);
    }

    private static JsonObject merge(String id, JsonObject imported, @Nullable JsonObject authored) {
        JsonObject defaults = WeaponPlaceholders.create(id);
        // The placeholder's class guess must not mask the exported weapon type.
        defaults.remove("ammo_type");
        if (imported.has("magazine_size") && defaults.get("reserve_ammo").getAsInt() > 0) {
            defaults.addProperty("reserve_ammo", imported.get("magazine_size").getAsInt() * 3);
        }
        Supply supply = SUPPLIES.get(id);
        if (supply != null) {
            defaults.addProperty("reserve_ammo", supply.reserve());
            defaults.addProperty("reload_time_s", supply.reloadSeconds());
        }
        if (authored != null) {
            defaults = overlay(defaults, authored);
        }
        JsonObject merged = overlay(defaults, imported);
        // CS ammo enums are not Minecraft item ids; retain authored pack item mappings.
        if (authored != null && authored.has("ammo_type")) {
            merged.add("ammo_type", authored.get("ammo_type").deepCopy());
        }
        return merged;
    }

    /** Public for offline import checks; source text is never included in distributed resources. */
    public static Map<String, JsonObject> parse(String text) {
        JsonObject source = Kv3Parser.parse(text);
        Map<String, JsonObject> resolved = new LinkedHashMap<>();
        Map<String, JsonObject> imported = new LinkedHashMap<>();
        for (String key : source.keySet()) {
            if (!key.startsWith("weapon_") || key.endsWith("_prefab") || !source.get(key).isJsonObject()) {
                continue;
            }
            String id = key.substring("weapon_".length());
            try {
                JsonObject json = convert(id, resolve(source, key, resolved, new HashSet<>()));
                if (json != null) {
                    imported.put(id, json);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[csmc] Cannot import CS2 weapon {}: {}; retaining its authored fallback", id, e.getMessage());
            }
        }
        return Map.copyOf(imported);
    }

    private static JsonObject convert(String id, JsonObject values) {
        JsonElement clip = primary(values.get("m_iMaxClip1"));
        JsonElement melee = primary(values.get("m_bMeleeWeapon"));
        if (clip == null || clip.getAsInt() < 1 || (melee != null && melee.getAsBoolean())) {
            return null; // Base entries, knives and grenades contain dummy gun statistics.
        }
        JsonObject json = new JsonObject();
        json.addProperty("id", "csmc:" + id);
        json.addProperty("display_name", id.replace('_', ' '));
        for (Map.Entry<String, String> field : DIRECT.entrySet()) {
            copy(values, field.getValue(), json, field.getKey());
        }
        JsonElement type = primary(values.get("m_WeaponType"));
        json.addProperty("ammo_type", AmmoType.fromWeaponType(id, type == null ? "" : type.getAsString()).key());
        JsonObject inaccuracy = new JsonObject();
        for (String stance : STANCES) {
            copy(values, "m_flInaccuracy" + capitalize(stance), inaccuracy, stance);
        }
        json.add("inaccuracy", inaccuracy);
        JsonElement zoomLevels = primary(values.get("m_nZoomLevels"));
        if (zoomLevels != null && zoomLevels.isJsonPrimitive()) {
            int count = zoomLevels.getAsInt();
            json.add("zoom", zoom(values, count));
            if (count > 0) {
                json.add("scoped", scoped(values));
            }
        }
        JsonObject recoil = new JsonObject();
        copy(values, "m_flRecoilAngle", recoil, "angle");
        copy(values, "m_flRecoilAngleVariance", recoil, "angle_variance");
        copy(values, "m_flRecoilMagnitude", recoil, "magnitude");
        copy(values, "m_flRecoilMagnitudeVariance", recoil, "magnitude_variance");
        copy(values, "m_nRecoilSeed", recoil, "seed");
        json.add("recoil", recoil);
        JsonObject recovery = new JsonObject();
        copy(values, "m_flRecoveryTimeStand", recovery, "stand");
        copy(values, "m_flRecoveryTimeCrouch", recovery, "crouch");
        // CS2 writes -1 for "no final recovery time": the base time applies throughout the spray.
        copyFinal(values, "m_flRecoveryTimeStandFinal", "m_flRecoveryTimeStand", recovery, "stand_final");
        copyFinal(values, "m_flRecoveryTimeCrouchFinal", "m_flRecoveryTimeCrouch", recovery, "crouch_final");
        copy(values, "m_nRecoveryTransitionStartBullet", recovery, "start_bullet");
        copy(values, "m_nRecoveryTransitionEndBullet", recovery, "end_bullet");
        json.add("recovery", recovery);
        return json;
    }

    /** Cache fields override placeholders, but absent cache fields retain the pack's authored defaults. */
    public static JsonObject overlay(JsonObject defaults, JsonObject imported) {
        JsonObject result = defaults.deepCopy();
        for (Map.Entry<String, JsonElement> entry : imported.entrySet()) {
            if (entry.getValue().isJsonObject() && result.has(entry.getKey())
                    && result.get(entry.getKey()).isJsonObject()) {
                result.add(entry.getKey(), overlay(result.getAsJsonObject(entry.getKey()), entry.getValue().getAsJsonObject()));
            } else {
                result.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }

    private static JsonObject resolve(JsonObject root, String name, Map<String, JsonObject> memo, Set<String> visiting) {
        JsonObject cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        if (!root.has(name) || !root.get(name).isJsonObject()) {
            throw new JsonParseException("Missing weapon prefab '" + name + "'");
        }
        if (!visiting.add(name)) {
            throw new JsonParseException("Cyclic weapon prefab '" + name + "'");
        }
        JsonObject entry = root.getAsJsonObject(name);
        JsonObject result = new JsonObject();
        JsonElement base = entry.get("_base");
        if (base == null) {
            base = entry.get("_prefab");
        }
        if (base != null && !base.isJsonNull()) {
            if (base.isJsonArray()) {
                for (JsonElement parent : base.getAsJsonArray()) {
                    result = overlay(result, resolve(root, parent.getAsString(), memo, visiting));
                }
            } else {
                for (String parent : base.getAsString().trim().split("\\s+")) {
                    if (!parent.isBlank()) {
                        result = overlay(result, resolve(root, parent, memo, visiting));
                    }
                }
            }
        }
        result = overlay(result, entry);
        visiting.remove(name);
        memo.put(name, result);
        return result;
    }

    private static void copy(JsonObject source, String sourceName, JsonObject target, String targetName) {
        JsonElement value = primary(source.get(sourceName));
        if (value != null && value.isJsonPrimitive()) {
            target.add(targetName, value.deepCopy());
        }
    }

    /**
     * Copies a final recovery time; CS2's -1 sentinel ("no final time") becomes the base time, so the
     * authored/placeholder final is not used for a weapon that has none.
     */
    private static void copyFinal(JsonObject source, String finalName, String baseName, JsonObject target, String targetName) {
        JsonElement value = primary(source.get(finalName));
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsDouble() < 0.0) {
            value = primary(source.get(baseName));
        }
        if (value != null && value.isJsonPrimitive()) {
            target.add(targetName, value.deepCopy());
        }
    }

    /** {@code m_nZoomFOV1..count} (CS horizontal degrees), {@code m_flZoomTime0..}, {@code m_bUnzoomsAfterShot}. */
    private static JsonObject zoom(JsonObject values, int count) {
        JsonObject zoom = new JsonObject();
        JsonArray levels = new JsonArray();
        for (int level = 1; level <= count; level++) {
            JsonElement fov = primary(values.get("m_nZoomFOV" + level));
            if (fov == null || !fov.isJsonPrimitive()) {
                throw new JsonParseException("m_nZoomLevels is " + count + " but m_nZoomFOV" + level + " is missing");
            }
            levels.add(fov.deepCopy());
        }
        zoom.add("levels", levels);
        JsonElement defaultTime = primary(values.get("m_flZoomTime0"));
        JsonArray times = new JsonArray();
        boolean hasTime = false;
        for (int level = 0; level <= Math.max(2, count); level++) {
            JsonElement time = primary(values.get("m_flZoomTime" + level));
            if (time == null) {
                if (defaultTime == null) {
                    times.add(WeaponData.Zoom.DEFAULT_TIME_S);
                } else {
                    times.add(defaultTime.deepCopy());
                }
            } else {
                times.add(time.deepCopy());
                hasTime = true;
            }
        }
        if (hasTime) {
            zoom.add("time_s", times.get(0).deepCopy());
            zoom.add("times_s", times);
        }
        copy(values, "m_bUnzoomsAfterShot", zoom, "unzoom_after_shot");
        return zoom;
    }

    /** Scoped mode: second element of each {@code CFiringModeFloat}; scalar exports apply to both modes. */
    private static JsonObject scoped(JsonObject values) {
        JsonObject scoped = new JsonObject();
        copySecondary(values, "m_flSpread", scoped, "spread_base");
        copySecondary(values, "m_flMaxSpeed", scoped, "max_speed");
        copySecondary(values, "m_flCycleTime", scoped, "cycle_time_s");
        JsonObject inaccuracy = new JsonObject();
        for (String stance : STANCES) {
            copySecondary(values, "m_flInaccuracy" + capitalize(stance), inaccuracy, stance);
        }
        scoped.add("inaccuracy", inaccuracy);
        return scoped;
    }

    private static void copySecondary(JsonObject source, String sourceName, JsonObject target, String targetName) {
        JsonElement element = source.get(sourceName);
        JsonElement value = element != null && element.isJsonArray() && element.getAsJsonArray().size() > 1
                ? primary(element.getAsJsonArray().get(1))
                : primary(element);
        if (value != null && value.isJsonPrimitive()) {
            target.add(targetName, value.deepCopy());
        }
    }

    private static JsonElement primary(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().isEmpty() ? null : primary(element.getAsJsonArray().get(0));
        }
        return element;
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
