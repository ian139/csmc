package dev.csmc.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-weapon {@code sounds/<id>/manifest.json}: Source 2 sound events grouped by action. Only plain
 * OGG file names inside the weapon directory are kept (the client decodes OGG only).
 */
public final class GunSoundManifest {
    public static final GunSoundManifest EMPTY = new GunSoundManifest(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    private static final Pattern FILE = Pattern.compile("[a-z0-9_.-]+\\.ogg");

    /** One reload sound at {@code phase} (0..1) of the server reload time; one file is picked at random. */
    public record ReloadPart(String event, List<String> files, float phase) {}

    private record Event(String name, String shortName, String action, List<String> files) {}

    private final List<String> fireNear;
    private final List<String> fireDistant;
    private final List<String> draw;
    private final List<String> zoomIn;
    private final List<String> zoomOut;
    private final List<ReloadPart> reloadParts;

    private GunSoundManifest(List<String> fireNear, List<String> fireDistant, List<String> draw,
                             List<String> zoomIn, List<String> zoomOut, List<ReloadPart> reloadParts) {
        this.fireNear = fireNear;
        this.fireDistant = fireDistant;
        this.draw = draw;
        this.zoomIn = zoomIn;
        this.zoomOut = zoomOut;
        this.reloadParts = reloadParts;
    }

    /** Missing manifest yields {@link #EMPTY}. */
    public static GunSoundManifest read(Path weaponDir) throws IOException {
        Path file = weaponDir.resolve("manifest.json");
        if (!Files.isRegularFile(file)) return EMPTY;
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static GunSoundManifest parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Event> events = new ArrayList<>();
        if (root.has("events") && root.get("events").isJsonObject()) {
            for (var entry : root.getAsJsonObject("events").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject event = entry.getValue().getAsJsonObject();
                JsonElement action = event.get("action");
                List<String> files = files(event);
                if (action == null || !action.isJsonPrimitive() || files.isEmpty()) continue;
                String name = entry.getKey();
                String shortName = name.substring(name.indexOf('.') + 1).replace(".", "").toLowerCase(Locale.ROOT);
                events.add(new Event(name, shortName, action.getAsString(), files));
            }
        }
        List<Event> fire = events.stream().filter(e -> e.action.equals("fire")).toList();
        List<Event> silenced = fire.stream().filter(e -> e.shortName.contains("silenced")).toList();
        List<String> near = flatten(!silenced.isEmpty() ? silenced
                : fire.stream().filter(e -> !e.shortName.contains("distant") && !e.shortName.contains("focused")).toList());
        if (near.isEmpty()) near = flatten(fire.stream().filter(e -> !e.shortName.contains("distant")).toList());
        // The distant layer is the unsuppressed report; a silenced weapon has none.
        List<String> distant = silenced.isEmpty()
                ? flatten(fire.stream().filter(e -> e.shortName.contains("distant")).toList()) : List.of();
        List<String> draw = flatten(events.stream().filter(e -> e.action.equals("draw")).toList());
        List<String> zoomIn = flatten(events.stream().filter(e -> e.action.equals("zoom_in")).toList());
        List<String> zoomOut = flatten(events.stream().filter(e -> e.action.equals("zoom_out")).toList());
        return new GunSoundManifest(near, distant, draw, zoomIn, zoomOut, reloadParts(events));
    }

    private static List<ReloadPart> reloadParts(List<Event> events) {
        List<Event> reload = events.stream().filter(e -> e.action.equals("reload")).toList();
        Set<String> names = new HashSet<>();
        for (Event event : reload) names.add(event.shortName);
        boolean hasInsert = reload.stream().anyMatch(e -> !e.shortName.contains("addammo") && isInsert(e.shortName));
        List<ReloadPart> parts = new ArrayList<>();
        for (Event event : reload) {
            String name = event.shortName;
            // "_q" events are quick-reload duplicates of their base event.
            if (name.endsWith("_q") && names.contains(name.substring(0, name.length() - 2))) continue;
            if (name.contains("addammo") && hasInsert) continue;
            parts.add(new ReloadPart(event.name, event.files, phase(name)));
        }
        parts.sort(Comparator.comparingDouble(ReloadPart::phase));
        return List.copyOf(parts);
    }

    private static boolean isInsert(String name) {
        return name.contains("clipin") || name.contains("insert") || name.contains("boxin");
    }

    /** Position within the reload, from CS2 event names (clip out → in → bolt/slide/pump). */
    static float phase(String name) {
        if (name.contains("start")) return 0f;
        if (name.contains("coverup")) return .05f;
        if (name.contains("out") || name.contains("cliprelease")) return name.endsWith("2") ? .15f : .1f;
        if (name.contains("touch")) return .35f;
        if (name.contains("boxin") || name.contains("insertshell")) return .4f;
        if (name.contains("chain")) return .45f;
        if (name.startsWith("rclipin")) return .55f;
        if (name.contains("clipslide") || name.contains("clipinslide")) return .5f;
        if (name.contains("clipin")) return .45f;
        if (name.contains("hit")) return .55f;
        if (name.contains("addammo")) return .5f;
        if (name.contains("coverdown")) return .65f;
        if (name.contains("back") || name.contains("pull")) return name.endsWith("2") ? .78f : .75f;
        if (name.contains("forward") || name.contains("release") || name.contains("pump")) return .88f;
        return .3f;
    }

    private static List<String> files(JsonObject event) {
        if (!event.has("files") || !event.get("files").isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement file : event.getAsJsonArray("files")) {
            if (file.isJsonPrimitive() && FILE.matcher(file.getAsString()).matches() && !file.getAsString().startsWith(".")) {
                out.add(file.getAsString());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> flatten(List<Event> events) {
        Set<String> out = new LinkedHashSet<>();
        for (Event event : events) out.addAll(event.files);
        return List.copyOf(out);
    }

    public List<String> fireNear() { return fireNear; }
    public List<String> fireDistant() { return fireDistant; }
    public List<String> draw() { return draw; }
    public List<String> zoomIn() { return zoomIn; }
    public List<String> zoomOut() { return zoomOut; }
    public List<ReloadPart> reloadParts() { return reloadParts; }

    /** Every file any action may play. */
    public Set<String> allFiles() {
        Set<String> all = new LinkedHashSet<>(fireNear);
        all.addAll(fireDistant);
        all.addAll(draw);
        all.addAll(zoomIn);
        all.addAll(zoomOut);
        for (ReloadPart part : reloadParts) all.addAll(part.files());
        return all;
    }
}
