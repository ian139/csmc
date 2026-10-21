package dev.csmc.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only view of the exporter's {@code manifest.json}: per-weapon model file, clips, meshes, clip mapping. */
public final class CacheManifest {
    public static final CacheManifest EMPTY = new CacheManifest(Map.of());
    /** Clip names tried, in order, when the manifest has no explicit mapping for an action. */
    private static final Map<String, List<String>> CLIP_ALIASES = Map.of(
            "fire", List.of("shoot", "fire", "attack"),
            "reload", List.of("reload"),
            "draw", List.of("draw", "deploy"),
            "inspect", List.of("inspect", "inventory_inspect"));

    private final Map<String, Weapon> weapons;

    private CacheManifest(Map<String, Weapon> weapons) {
        this.weapons = weapons;
    }

    /**
     * @param modelFile cache-relative world model path, or null
     * @param animations exporter action→clip mapping (may be empty)
     */
    public record Weapon(String id, String status, String modelFile, List<String> clips, List<String> meshes,
                         Map<String, String> animations) {
        /** Exporter mapping first, then a clip whose name matches a known alias; null when the model lacks one. */
        public String clipFor(String action) {
            String mapped = animations.get(action);
            if (mapped != null && !mapped.isBlank()) return mapped;
            for (String alias : CLIP_ALIASES.getOrDefault(action, List.of(action))) {
                for (String clip : clips) if (clip.equalsIgnoreCase(alias)) return clip;
            }
            return null;
        }
    }

    /** Missing manifest yields {@link #EMPTY}; malformed JSON is an error. */
    public static CacheManifest read(Path root) throws IOException {
        Path file = root.resolve("manifest.json");
        if (!Files.isRegularFile(file)) return EMPTY;
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static CacheManifest parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Weapon> weapons = new HashMap<>();
        if (root.has("weapons") && root.get("weapons").isJsonObject()) {
            for (var entry : root.getAsJsonObject("weapons").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject weapon = entry.getValue().getAsJsonObject();
                JsonObject world = object(object(weapon, "models"), "world");
                Map<String, String> animations = new LinkedHashMap<>();
                JsonObject mapping = object(weapon, "animations");
                if (mapping != null) {
                    for (var clip : mapping.entrySet()) {
                        if (clip.getValue().isJsonPrimitive()) animations.put(clip.getKey(), clip.getValue().getAsString());
                    }
                }
                weapons.put(entry.getKey(), new Weapon(entry.getKey(), string(weapon, "status"), string(world, "file"),
                        strings(world, "clips"), strings(world, "meshes"), Map.copyOf(animations)));
            }
        }
        return new CacheManifest(Map.copyOf(weapons));
    }

    /** Entry for a weapon id, or null. */
    public Weapon weapon(String id) {
        return weapons.get(id.toLowerCase(Locale.ROOT));
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static List<String> strings(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) return List.of();
        JsonArray array = parent.getAsJsonArray(key);
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement element : array) if (element.isJsonPrimitive()) out.add(element.getAsString());
        return List.copyOf(out);
    }
}
