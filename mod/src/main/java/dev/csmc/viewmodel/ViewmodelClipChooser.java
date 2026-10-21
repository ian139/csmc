package dev.csmc.viewmodel;

import java.util.random.RandomGenerator;

/**
 * Chooses the exported CS2 viewmodel clips a weapon plays: the segments of each new action playback and the idle
 * clip for each idle loop. Not thread-safe: one instance per weapon, used from the render thread.
 */
public interface ViewmodelClipChooser {
    /** Clip length in seconds for a clip name (0 when unknown). */
    @FunctionalInterface
    interface Durations {
        float duration(String clip);
    }

    /**
     * Fills {@code out} (cleared first) with the clips a new playback of {@code cue} runs through. Clips that must
     * match gameplay timing, such as a reload, are time-scaled to {@link ViewmodelCue#duration()}. Leaves
     * {@code out} empty when the action has no clip.
     */
    void plan(ViewmodelCue cue, Durations durations, RandomGenerator random, ClipPlan out);

    /** Idle clip for loop number {@code loop} (0, 1, ...) with {@code magazine} rounds (-1 unknown); null if none. */
    String idle(int loop, int magazine);
}
