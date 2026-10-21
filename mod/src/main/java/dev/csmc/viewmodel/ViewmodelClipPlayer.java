package dev.csmc.viewmodel;

import java.util.random.RandomGenerator;

/**
 * First-person clip playback for one rendered weapon: which clip plays this frame and at what time. A new playback
 * (a new {@link ViewmodelCue} by identity, so each server event plans afresh) asks the {@link ViewmodelClipChooser}
 * for its {@link ClipPlan}; while the plan runs its segments play back to back; otherwise the idle loops, alternating
 * variants per completed loop and following the magazine (empty idles). When the running action has no clip, the
 * caller's fallback window applies instead.
 * Not thread-safe; allocation-free per update.
 */
public final class ViewmodelClipPlayer {
    private final ClipPlan plan = new ClipPlan();
    private ViewmodelCue playback;
    private boolean idling;
    private int idleLoop;
    private long idleStarted;
    private String clip;
    private float time;

    /** The clip chosen by the last successful {@link #update}. */
    public String clip() {
        return clip;
    }

    /** Seconds into {@link #clip()}. */
    public float time() {
        return time;
    }

    /** Forgets the running action and idle loop (disconnect, reload). */
    public void reset() {
        playback = null;
        plan.clear();
        idling = false;
        idleLoop = 0;
        clip = null;
    }

    /**
     * Chooses this frame's clip. {@code cue} is the running action (null when none), {@code seconds} its age,
     * {@code fallbackSeconds} how long the caller's own motion runs when the action has no clip, and
     * {@code magazine} the rounds now held (-1 unknown) for the idle pose. Returns false when the caller should use
     * that fallback (or has no idle clip).
     */
    public boolean update(ViewmodelClipChooser chooser, ViewmodelClipChooser.Durations durations, ViewmodelCue cue,
                          float seconds, float fallbackSeconds, int magazine, long nanos, RandomGenerator random) {
        if (cue != playback) {
            playback = cue;
            plan.clear();
            if (cue != null) chooser.plan(cue, durations, random, plan);
        }
        if (!plan.isEmpty() && seconds < plan.total()) {
            float at = Math.max(0, seconds);
            int segment = plan.segment(at);
            clip = plan.clip(segment);
            time = plan.clipTime(segment, at);
            idling = false;
            return true;
        }
        if (cue != null && plan.isEmpty() && seconds < fallbackSeconds) {
            idling = false;
            return false;
        }
        if (!idling) {
            idling = true;
            idleStarted = nanos;
        }
        String idle = chooser.idle(idleLoop, magazine);
        if (idle == null) return false;
        long length = (long) (durations.duration(idle) * 1_000_000_000L);
        if (length <= 0) {
            // A single-frame idle pose (CS2 gun idles): hold it.
            clip = idle;
            time = 0;
            return true;
        }
        if (nanos - idleStarted >= length) {
            // Advance whole loops; each completed loop moves to the next idle variant.
            long loops = (nanos - idleStarted) / length;
            idleStarted += loops * length;
            idleLoop += (int) Math.min(loops, Integer.MAX_VALUE);
            idle = chooser.idle(idleLoop, magazine);
            length = (long) (durations.duration(idle) * 1_000_000_000L);
            if (length <= 0) return false;
            if (nanos - idleStarted >= length) idleStarted = nanos;
        }
        clip = idle;
        time = (nanos - idleStarted) / 1_000_000_000f;
        return true;
    }
}
