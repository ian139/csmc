package dev.csmc.viewmodel;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Plans a gun's exported CS2 viewmodel clips per gameplay action. The cache manifest's {@code viewmodel_animations}
 * maps each action to one clip; only clips that loaded are used. Everything that varies per event comes from the
 * {@link ViewmodelCue}, i.e. from the server event, never from the held stack:
 * <ul>
 *   <li>{@code fire}: the fire clip, or {@code fire_last} when the shot emptied the magazine. The Dual Berettas
 *       ({@code fire_right}/{@code fire_left}) fire left when the shot leaves an odd count and right when it leaves an
 *       even one, so the left pistol empties first and the side never depends on which events were rendered;
 *       {@code fire_left_last}/{@code fire_right_last} play for each pistol's last round. Then the {@code cycle} clip
 *       when the bolt/pump cycle is separate (otherwise CS2 bakes it into the shot). Natural speed: each server shot
 *       restarts it, so automatic fire restarts at the fire rate.</li>
 *   <li>{@code reload}: {@code reload_empty} from an empty magazine when mapped. Shotguns with {@code reload_start}
 *       and {@code reload_loop} run start, one loop per shell the server loads and {@code reload_end}; a single-shell
 *       gun with only a {@code reload} clip (CS2 bakes one shell into it) loops that clip once per shell. The whole
 *       sequence is time-scaled to the server's reload duration.</li>
 *   <li>Everything else ({@code draw}, {@code inspect}, ...) plays its own clip at natural speed.</li>
 *   <li>Idle: {@code idle_empty} with an empty magazine, {@code idle_left_empty} with only the Berettas' left pistol
 *       empty (one round), else {@code idle}.</li>
 * </ul>
 * Not thread-safe: one instance per gun, used from the render thread.
 */
public final class GunClipChooser implements ViewmodelClipChooser {
    /** Actions whose clips a gun loads, in load order: the ones gameplay plays. */
    public static final List<String> ACTIONS = List.of("idle", "idle_empty", "idle_left_empty", "draw", "fire",
            "fire_last", "fire_left", "fire_left_last", "fire_right", "fire_right_last", "cycle", "reload",
            "reload_empty", "reload_start", "reload_loop", "reload_end", "inspect");

    private final Map<String, String> clips = new HashMap<>();
    private final boolean singleShells;
    private final boolean dual;

    /**
     * {@code animations} maps action → clip name; {@code loaded} are the clips available; {@code singleShells}
     * marks a gun that reloads one shell at a time (CS2 {@code m_bReloadsSingleShells}).
     */
    public GunClipChooser(Map<String, String> animations, Collection<String> loaded, boolean singleShells) {
        this.singleShells = singleShells;
        for (var entry : animations.entrySet()) {
            if (entry.getValue() != null && loaded.contains(entry.getValue())) clips.put(entry.getKey(), entry.getValue());
        }
        dual = clips.containsKey("fire_right") && clips.containsKey("fire_left");
    }

    /** The clip mapped to {@code action}, or null. */
    public String clip(String action) {
        return clips.get(action);
    }

    @Override
    public void plan(ViewmodelCue cue, Durations durations, RandomGenerator random, ClipPlan out) {
        out.clear();
        int magazine = cue.magazine();
        switch (cue.action()) {
            case "fire" -> {
                String fire;
                if (dual) {
                    boolean left = magazine >= 0 && magazine % 2 == 1;
                    fire = left ? pick(magazine == 1, "fire_left_last", "fire_left")
                            : pick(magazine == 0, "fire_right_last", "fire_right");
                } else {
                    fire = pick(magazine == 0, "fire_last", "fire");
                }
                add(out, fire, durations);
                if (fire != null) add(out, clips.get("cycle"), durations);
            }
            case "reload" -> {
                String start = clips.get("reload_start");
                String loop = clips.get("reload_loop");
                String reload = pick(magazine == 0, "reload_empty", "reload");
                int shells = Math.max(1, cue.rounds());
                if (start != null && loop != null) {
                    add(out, start, durations);
                    for (int i = 0; i < Math.min(shells, ClipPlan.CAPACITY - 2); i++) add(out, loop, durations);
                    add(out, clips.get("reload_end"), durations);
                } else if (singleShells && reload != null) {
                    for (int i = 0; i < Math.min(shells, ClipPlan.CAPACITY); i++) add(out, reload, durations);
                } else {
                    add(out, reload, durations);
                }
                out.scaleTo(cue.duration());
            }
            default -> add(out, clips.get(cue.action()), durations);
        }
    }

    @Override
    public String idle(int loop, int magazine) {
        if (magazine == 0) return pick(true, "idle_empty", "idle");
        return pick(dual && magazine == 1, "idle_left_empty", "idle");
    }

    /** {@code variant}'s clip when {@code useVariant} and it exists, else {@code base}'s. */
    private String pick(boolean useVariant, String variant, String base) {
        String clip = useVariant ? clips.get(variant) : null;
        return clip != null ? clip : clips.get(base);
    }

    private static void add(ClipPlan out, String clip, Durations durations) {
        if (clip != null) out.add(clip, durations.duration(clip));
    }
}
