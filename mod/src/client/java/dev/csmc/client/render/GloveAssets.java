package dev.csmc.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The cache manifest's {@code gloves.<glove_id>} records: {@code status}, {@code file} (under {@code models/}) and
 * {@code meshes.viewmodel} shaped like an {@code arms.parts} mesh ({@code node}, {@code joint_anchors}). Read once
 * per resource generation; {@link #clear} on reload. Thread-safe: the loader threads and the render thread share it.
 */
final class GloveAssets {
    /** A usable glove viewmodel mesh: the glTF file and its viewmodel mesh node, plus that mesh's manifest record. */
    record Glove(String id, Path file, String node, JsonObject mesh) { }

    private static Map<String, Glove> gloves;

    private GloveAssets() {}

    static synchronized void clear() {
        gloves = null;
    }

    /** The glove's exported viewmodel mesh, or null when it is missing, failed or outside the cache. */
    static synchronized Glove glove(String id) {
        if (gloves == null) gloves = read();
        return gloves.get(id);
    }

    private static Map<String, Glove> read() {
        Map<String, Glove> out = new HashMap<>();
        try {
            Path root = CachePaths.root().toAbsolutePath().normalize();
            Path manifest = root.resolve("manifest.json");
            if (!Files.isRegularFile(manifest)) return out;
            JsonObject records = object(JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject(), "gloves");
            if (records == null) return out;
            for (var entry : records.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject record = entry.getValue().getAsJsonObject();
                // "in_progress" while an export run refreshes finishes; the mesh file is written first.
                if ("failed".equals(string(record, "status"))) continue;
                String file = string(record, "file");
                JsonObject mesh = object(object(record, "meshes"), "viewmodel");
                String node = string(mesh, "node");
                if (file == null || node == null) continue;
                Path path = root.resolve(file).normalize();
                if (!path.startsWith(root.resolve("models")) || !Files.isRegularFile(path)) continue;
                out.put(entry.getKey(), new Glove(entry.getKey(), path, node, mesh));
            }
        } catch (Exception e) {
            CsmcMod.LOGGER.warn("[csmc glTF] Cannot read glove records; gloves use the default arms", e);
        }
        return out;
    }

    static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
