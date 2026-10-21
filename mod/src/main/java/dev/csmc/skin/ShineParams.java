package dev.csmc.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Specular inputs of one finish, read from the exporter's {@code skins/<weapon>/<kit>/shine.json} (or
 * {@code skins/<weapon>/_default/shine.json} for the unskinned weapon). A cache without these files, or a file with
 * an unknown {@code shine_schema}, yields {@code null}: the renderer keeps the matte base-colour look.
 *
 * <p>Values are quantized to 1/32 so equal-looking finishes share one render layer.
 */
public record ShineParams(float roughness, float metalness, float pearlescence) {
    public static final int SCHEMA = 1;
    public static final String FILE = "shine.json";
    public static final String DEFAULT_KIT = "_default";
    private static final float STEPS = 32f;
    private static final float MIN_ROUGHNESS = 0.04f;

    public ShineParams {
        roughness = Math.max(MIN_ROUGHNESS, quantize(roughness));
        metalness = quantize(metalness);
        pearlescence = quantize(pearlescence);
    }

    /** Parses one shine.json; {@code null} for missing, malformed or newer-schema documents. */
    public static ShineParams parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            if (!object.has("shine_schema") || object.get("shine_schema").getAsInt() != SCHEMA) return null;
            return new ShineParams(number(object, "roughness", 0.6f), number(object, "metalness", 0f),
                    number(object, "pearlescence", 0f));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float number(JsonObject object, String key, float fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
        float number = value.getAsFloat();
        return Float.isFinite(number) ? number : fallback;
    }

    private static float quantize(float value) {
        if (!Float.isFinite(value)) return 0f;
        return Math.round(Math.max(0f, Math.min(1f, value)) * STEPS) / STEPS;
    }
}
