package dev.csmc.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.csmc.CsmcMod;
import dev.csmc.client.gltf.GltfLoader;
import dev.csmc.client.gltf.GltfModel;
import dev.csmc.client.gltf.GltfPose;
import dev.csmc.data.CachePaths;
import dev.csmc.data.PriorityExecutor;
import dev.csmc.melee.KnifeClipChooser;
import dev.csmc.viewmodel.GunClipChooser;
import dev.csmc.viewmodel.EquipmentClipChooser;
import dev.csmc.viewmodel.ViewmodelClipChooser;
import dev.csmc.viewmodel.ViewmodelClipPlayer;
import dev.csmc.viewmodel.ViewmodelFraming;
import dev.csmc.viewmodel.ViewmodelCue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.joml.Matrix4f;

/**
 * Exported CS2 viewmodel clips for knives and guns, played in first person as CS2 does. Each clip is its own glTF
 * holding two skeletons sampled together: the shared arm skeleton (with bone {@code wpn}) and the weapon's own
 * skeleton ({@code weapon}, plus {@code blade}/{@code weapon_hand_l} for knives or {@code bolt}, {@code clip},
 * {@code slide}, {@code trigger}, ... for guns). Every frame the chosen clip is sampled once; the weapon model's nodes
 * take the clip's local transforms by name, so magazines, bolts, slides, triggers, blades and both Shadow Daggers
 * move as authored, and the model's {@code weapon} bone is attached to the arm skeleton's {@code wpn} bone. The
 * result is placed in Minecraft view space (camera at the origin, glTF viewmodel metres +Z forward/+X left).
 *
 * <p>Knife records map actions through {@code animations} ({@link KnifeClipChooser}: variants per action); gun
 * records through {@code viewmodel_animations} ({@link GunClipChooser}: reloads scaled to the server's duration,
 * shotgun shell loops, Dual Beretta alternation, separate bolt/pump cycles). Both list clip files in
 * {@code viewmodel_clips}. A weapon's clips load once, together, on the bounded glTF loader the first time it is drawn
 * in first person; until then, or when an action has no clip, the caller's own rendering applies.
 * Render thread only; sampling reuses each clip's pose buffers and allocates nothing per frame.
 */
final class ViewmodelClips {
    private static final Map<String, Entry> WEAPONS = new HashMap<>();
    private static final Matrix4f WEAPON = new Matrix4f();

    private ViewmodelClips() {}

    /**
     * One loaded clip on its own skeleton pair. The clip data is shared and immutable; {@code pose} is scratch that
     * is sampled and consumed within one draw, on the render thread.
     */
    static final class Track {
        final GltfModel model;
        final GltfModel.Clip clip;
        final GltfPose pose;
        final int wpn;
        /** Node maps into each weapon model drawn with this clip (default/legacy body), built once per model. */
        private final Map<GltfModel, ModelMap> maps = new IdentityHashMap<>(2);

        Track(GltfModel model, GltfModel.Clip clip, int wpn) {
            this.model = model;
            this.clip = clip;
            this.wpn = wpn;
            pose = model.newPose();
        }

        float duration() { return clip.duration(); }

        private ModelMap map(GltfModel target) {
            ModelMap map = maps.get(target);
            if (map == null) {
                map = new ModelMap(GltfPose.nodeMap(target, model), target.nodeIndex("weapon"));
                maps.put(target, map);
            }
            return map;
        }
    }

    private record ModelMap(int[] nodes, int weapon) {}

    /** One weapon's loaded clips and the main hand's playback (the only hand that draws the clip viewmodel). */
    static final class Weapon {
        final ViewmodelClipChooser chooser;
        final Map<String, Track> tracks;
        final ViewmodelClipPlayer player = new ViewmodelClipPlayer();
        private final ViewmodelClipChooser.Durations durations = this::duration;

        Weapon(ViewmodelClipChooser chooser, Map<String, Track> tracks) {
            this.chooser = chooser;
            this.tracks = tracks;
        }

        private float duration(String clip) {
            Track track = clip == null ? null : tracks.get(clip);
            return track == null ? 0 : track.duration();
        }
    }

    private static final class Entry {
        final PriorityExecutor.Task<Weapon> task;
        Weapon weapon;

        Entry(PriorityExecutor.Task<Weapon> task) { this.task = task; }
    }

    /** Drops loaded clips and playback (resource reload, disconnect); render thread only. */
    static void clear() {
        for (Entry entry : WEAPONS.values()) entry.task.cancelIfQueued();
        WEAPONS.clear();
    }

    /** The weapon's loaded clips, or null while loading or when it has none; starts the load. Render thread. */
    static Weapon weapon(PriorityExecutor loader, String id) {
        Entry entry = WEAPONS.get(id);
        if (entry == null) {
            entry = new Entry(loader.submit(() -> load(id), PriorityExecutor.Priority.FOREGROUND));
            WEAPONS.put(id, entry);
        }
        if (entry.weapon == null) {
            var future = entry.task.future();
            if (!future.isDone()) return null;
            entry.weapon = future.isCompletedExceptionally() || future.join() == null ? EMPTY : future.join();
            // Arm node maps for these clips are built now, once, rather than while drawing.
            entry.weapon.tracks.values().forEach(ViewmodelArms::prepare);
        }
        return entry.weapon == EMPTY ? null : entry.weapon;
    }

    /** Visits every track of every resolved weapon (preparing arms that loaded after the clips). */
    static void forEachTrack(java.util.function.Consumer<Track> action) {
        for (Entry entry : WEAPONS.values()) {
            if (entry.weapon != null) entry.weapon.tracks.values().forEach(action);
        }
    }

    private static final Weapon EMPTY = new Weapon(new KnifeClipChooser(Map.of(), Set.of()), Map.of());

    /**
     * Samples this frame's clip (see {@link ViewmodelClipPlayer}): the running {@code cue}'s plan, else the looping
     * idle for {@code magazine}. {@code fallbackSeconds} is how long the caller's own motion runs for an action
     * without a clip. Poses {@code target} (the weapon model's pose) by node name and writes the weapon model's
     * view-space placement to {@code placement}. Returns the sampled track, or null when the caller should use its
     * fallback.
     */
    static Track pose(Weapon weapon, ViewmodelCue cue, float seconds, float fallbackSeconds, int magazine,
                      GltfPose target, boolean left, Matrix4f placement) {
        ViewmodelClipPlayer player = weapon.player;
        if (!player.update(weapon.chooser, weapon.durations, cue, seconds, fallbackSeconds, magazine,
                System.nanoTime(), ThreadLocalRandom.current())) {
            return null;
        }
        Track track = weapon.tracks.get(player.clip());
        if (track == null) return null;
        ModelMap map = track.map(target.model());
        if (map.weapon < 0) return null;
        track.pose.setClip(track.clip, Math.min(player.time(), track.duration()));
        target.setFrom(track.pose, map.nodes);
        // weapon bone ↦ wpn bone: weapon model space → clip viewmodel space → Minecraft view space.
        ViewmodelFraming.clipToView(left, placement).mul(track.pose.world(track.wpn))
                .mul(WEAPON.set(target.world(map.weapon)).invert());
        return track;
    }

    /** Runs on the glTF loader: reads this weapon's manifest entry and parses every clip it may play (no meshes). */
    private static Weapon load(String id) {
        try {
            Path root = CachePaths.root().toAbsolutePath().normalize();
            JsonObject manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json"),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject weapons = manifest.has("weapons") ? manifest.getAsJsonObject("weapons") : manifest;
            JsonObject record = object(weapons, id);
            JsonObject files = object(record, "viewmodel_clips");
            // Gun records keep `animations` for their world-model clips; their viewmodel mapping is separate.
            JsonObject gunAnimations = object(record, "viewmodel_animations");
            boolean equipment = "equipment".equals(string(record, "kind"));
            boolean gun = gunAnimations != null && !equipment;
            JsonObject animations = gun || equipment ? gunAnimations : object(record, "animations");
            if (animations == null || files == null) return null;
            Map<String, String> mapped = new HashMap<>();
            for (var e : animations.entrySet()) {
                if (e.getValue().isJsonPrimitive()) mapped.put(e.getKey(), e.getValue().getAsString());
            }
            Set<String> wanted = new LinkedHashSet<>();
            if (gun || equipment) {
                for (String action : equipment ? EquipmentClipChooser.ACTIONS : GunClipChooser.ACTIONS) {
                    String clip = mapped.get(action);
                    if (clip != null) wanted.add(clip);
                }
            } else {
                KnifeClipChooser manifestChooser = new KnifeClipChooser(mapped, files.keySet());
                for (String action : KnifeClipChooser.ACTIONS) wanted.addAll(manifestChooser.variants(action));
            }
            Map<String, Track> tracks = new HashMap<>();
            for (String name : wanted) {
                Track track = load(root, files, name, gun || equipment);
                if (track != null) tracks.put(name, track);
            }
            CsmcMod.LOGGER.info("[csmc glTF] {}: viewmodel clips {}", id, tracks.keySet());
            if (tracks.isEmpty()) return null;
            // Choose only among clips that actually loaded, so a missing variant cannot stall idle or an action.
            Collection<String> loaded = tracks.keySet();
            JsonElement singleShells = record.get("viewmodel_reload_single_shells");
            ViewmodelClipChooser chooser = equipment ? new EquipmentClipChooser(mapped, loaded) : gun
                    ? new GunClipChooser(mapped, loaded, singleShells != null && singleShells.isJsonPrimitive()
                            && singleShells.getAsBoolean())
                    : new KnifeClipChooser(mapped, loaded);
            return new Weapon(chooser, Map.copyOf(tracks));
        } catch (Exception e) {
            CsmcMod.LOGGER.warn("[csmc glTF] Cannot load viewmodel clips for {}; using the default rendering", id, e);
            return null;
        }
    }

    /** Parses one clip, or null if unusable. Gun idles are single-frame poses, so guns keep zero-length clips. */
    private static Track load(Path root, JsonObject files, String clipName, boolean gun) throws Exception {
        JsonObject file = object(files, clipName);
        String relative = file == null ? null : string(file, "file");
        if (relative == null) return null;
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root.resolve("models")) || !Files.isRegularFile(path)) return null;
        GltfModel model = GltfLoader.load(path, GltfLoader.MeshFilter.NONE);
        int wpn = model.nodeIndex("wpn");
        if (wpn < 0 || model.clips.isEmpty()) return null;
        GltfModel.Clip clip = model.clips.values().iterator().next();
        if (clip.duration() < 0 || clip.duration() == 0 && !gun) return null;
        return new Track(model, clip, wpn);
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
