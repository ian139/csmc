package dev.csmc.viewmodel;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Plans an equipment item's (healthshot, C4, defuse kit) exported CS2 viewmodel clips per action. The cache manifest's
 * {@code viewmodel_animations} maps each action to one clip; only clips that loaded are used. Equipment has no firearm
 * rules: the magazine is ignored, and there are no fire, last-round or reload clips.
 * <ul>
 *   <li>{@code use}: the use clip (healthshot inject, C4 plant) at its natural length, time-scaled to the server's
 *       {@link ViewmodelCue#duration()} so the clip ends with the server-timed action. Without a server duration it
 *       plays at natural speed.</li>
 *   <li>{@code inspect}: one of the loaded {@code inspect}, {@code inspect_alt} or {@code inspect_alt2} clips, picked
 *       with one random draw per playback, at natural speed.</li>
 *   <li>Everything else ({@code draw}, ...) plays its own clip at natural speed.</li>
 *   <li>Idle: the mapped {@code idle} clip.</li>
 * </ul>
 * Not thread-safe: one instance per item, used from the render thread.
 */
public final class EquipmentClipChooser implements ViewmodelClipChooser {
    /** Actions whose clips an equipment item loads, in load order: the ones gameplay plays. */
    public static final List<String> ACTIONS = List.of("idle", "draw", "use", "inspect", "inspect_alt", "inspect_alt2");

    /** The inspect actions {@code inspect} chooses among, in order. */
    private static final List<String> INSPECTS = List.of("inspect", "inspect_alt", "inspect_alt2");

    private final Map<String, String> clips = new HashMap<>();
    private final List<String> loadedInspects;

    /**
     * {@code animations} maps action → clip name; {@code loaded} are the clips available. Only mapped, loaded clips of
     * {@link #ACTIONS} are kept.
     */
    public EquipmentClipChooser(Map<String, String> animations, Collection<String> loaded) {
        for (String action : ACTIONS) {
            String clip = animations.get(action);
            if (clip != null && loaded.contains(clip)) clips.put(action, clip);
        }
        loadedInspects = INSPECTS.stream().filter(clips::containsKey).toList();
    }

    @Override
    public void plan(ViewmodelCue cue, Durations durations, RandomGenerator random, ClipPlan out) {
        out.clear();
        switch (cue.action()) {
            case "use" -> {
                add(out, clips.get("use"), durations);
                out.scaleTo(cue.duration());
            }
            case "inspect" -> {
                if (!loadedInspects.isEmpty()) {
                    add(out, clips.get(loadedInspects.get(random.nextInt(loadedInspects.size()))), durations);
                }
            }
            default -> add(out, clips.get(cue.action()), durations);
        }
    }

    @Override
    public String idle(int loop, int magazine) {
        return clips.get("idle");
    }

    private static void add(ClipPlan out, String clip, Durations durations) {
        if (clip != null) out.add(clip, durations.duration(clip));
    }
}
