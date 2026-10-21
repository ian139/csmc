package dev.csmc.viewmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Equipment sound timing from the export cache: the viewmodel clip's authored {@code CNmSoundEvent} markers
 * ({@code weapons.<id>.viewmodel_markers}) matched by name to the sound manifest's events
 * ({@code sounds/<id>/manifest.json}). Marker times are clip seconds; {@link #cues} rescales them to the
 * server-timed duration (the 4 s C4 plant clip plays over the 3 s plant). Server events that have no clip marker
 * (beep, explode, success, ...) use the first manifest event of that sound action.
 */
public final class EquipmentSoundPlan {
    public static final EquipmentSoundPlan EMPTY = new EquipmentSoundPlan(Map.of(), Map.of(), Map.of());
    private static final Pattern FILE = Pattern.compile("[a-z0-9_.-]+\\.ogg");

    /** One sound at {@code seconds}; one of {@code files} is picked at random. */
    public record Cue(float seconds, List<String> files) {}

    private final Map<String, List<Cue>> timelines;
    private final Map<String, Float> clipSeconds;
    private final Map<String, List<String>> actionFiles;

    private EquipmentSoundPlan(Map<String, List<Cue>> timelines, Map<String, Float> clipSeconds,
                               Map<String, List<String>> actionFiles) {
        this.timelines = timelines;
        this.clipSeconds = clipSeconds;
        this.actionFiles = actionFiles;
    }

    /**
     * @param cacheManifest the cache {@code manifest.json} text, or null
     * @param soundManifest the equipment's {@code sounds/<id>/manifest.json} text, or null
     * @param available whether a manifest-listed file exists in the cache
     */
    public static EquipmentSoundPlan parse(String id, String cacheManifest, String soundManifest,
                                           Predicate<String> available) {
        if (soundManifest == null) return EMPTY;
        JsonObject sounds = JsonParser.parseString(soundManifest).getAsJsonObject();
        Map<String, List<String>> eventFiles = new HashMap<>();
        Map<String, List<String>> actionFiles = new HashMap<>();
        JsonObject events = object(sounds, "events");
        if (events != null) {
            for (Map.Entry<String, JsonElement> entry : events.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject event = entry.getValue().getAsJsonObject();
                List<String> files = files(event, available);
                if (files.isEmpty()) continue;
                eventFiles.put(entry.getKey(), files);
                String action = string(event, "action");
                if (action != null) actionFiles.putIfAbsent(action, files);
            }
        }

        Map<String, List<Cue>> timelines = new HashMap<>();
        Map<String, Float> clipSeconds = new HashMap<>();
        JsonObject record = cacheManifest == null ? null : record(JsonParser.parseString(cacheManifest), id);
        JsonObject animations = object(record, "viewmodel_animations");
        JsonObject clips = object(record, "viewmodel_clips");
        JsonObject markers = object(record, "viewmodel_markers");
        if (animations != null) {
            for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                String clip = entry.getValue().getAsString();
                JsonObject clipRecord = object(clips, clip);
                if (clipRecord != null && clipRecord.get("duration_seconds") instanceof JsonElement d
                        && d.isJsonPrimitive()) {
                    clipSeconds.put(entry.getKey(), d.getAsFloat());
                }
                List<Cue> cues = new ArrayList<>();
                if (markers != null && markers.get(clip) instanceof JsonArray list) {
                    for (JsonElement element : list) {
                        if (!element.isJsonObject()) continue;
                        JsonObject marker = element.getAsJsonObject();
                        List<String> files = eventFiles.get(string(marker, "name"));
                        if (!"CNmSoundEvent".equals(string(marker, "type")) || files == null) continue;
                        JsonElement time = marker.get("time_seconds");
                        if (time == null || !time.isJsonPrimitive()) continue;
                        cues.add(new Cue(Math.max(0, time.getAsFloat()), files));
                    }
                }
                cues.sort((a, b) -> Float.compare(a.seconds, b.seconds));
                if (!cues.isEmpty()) timelines.put(entry.getKey(), List.copyOf(cues));
            }
        }
        // No authored draw markers: the manifest's draw sound still plays when the item comes out.
        if (!timelines.containsKey("draw") && actionFiles.containsKey("draw")) {
            timelines.put("draw", List.of(new Cue(0, actionFiles.get("draw"))));
        }
        return new EquipmentSoundPlan(Map.copyOf(timelines), Map.copyOf(clipSeconds), Map.copyOf(actionFiles));
    }

    /**
     * Cues of a viewmodel action ({@code draw}, {@code use}) in played seconds; {@code playSeconds <= 0} keeps the
     * clip's own timing.
     */
    public List<Cue> cues(String action, float playSeconds) {
        List<Cue> cues = timelines.getOrDefault(action, List.of());
        Float authored = clipSeconds.get(action);
        if (playSeconds <= 0 || authored == null || authored <= 0 || cues.isEmpty()) return cues;
        float scale = playSeconds / authored;
        List<Cue> scaled = new ArrayList<>(cues.size());
        for (Cue cue : cues) scaled.add(new Cue(cue.seconds * scale, cue.files));
        return scaled;
    }

    /** Files of the first manifest event for a sound action ({@code beep}, {@code success}, ...); may be empty. */
    public List<String> files(String soundAction) {
        return actionFiles.getOrDefault(soundAction, List.of());
    }

    /** Every file this plan may play. */
    public Set<String> allFiles() {
        Set<String> all = new LinkedHashSet<>();
        for (List<Cue> cues : timelines.values()) for (Cue cue : cues) all.addAll(cue.files);
        for (List<String> files : actionFiles.values()) all.addAll(files);
        return all;
    }

    private static JsonObject record(JsonElement manifest, String id) {
        if (!manifest.isJsonObject()) return null;
        JsonObject root = manifest.getAsJsonObject();
        JsonObject weapons = root.has("weapons") ? object(root, "weapons") : root;
        return object(weapons, id);
    }

    private static List<String> files(JsonObject event, Predicate<String> available) {
        List<String> out = new ArrayList<>();
        if (event.get("files") instanceof JsonArray files) {
            for (JsonElement file : files) {
                if (!file.isJsonPrimitive()) continue;
                String name = file.getAsString();
                if (FILE.matcher(name).matches() && available.test(name)) out.add(name);
            }
        }
        return List.copyOf(out);
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
