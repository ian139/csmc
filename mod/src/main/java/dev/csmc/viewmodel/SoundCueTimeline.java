package dev.csmc.viewmodel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick-scheduled sound cues with per-owner, per-{@link Scope} cancellation. Each cue carries its owner's
 * generation for its scope when scheduled; {@link #cancel}, {@link #cancelAll} and {@link #clear} retire
 * generations, so a cue still in flight (waiting for its buffer to decode) is recognisably stale by
 * {@link #current}. The clock and dispatch stand still while paused. Not thread-safe.
 */
public final class SoundCueTimeline<T> {
    /** One-shot event sounds, or one hand's clip-marker timeline. */
    public enum Scope { EVENT, MAIN_HAND, OFF_HAND }

    public record Cue<T>(int owner, Scope scope, long generation, long dueTick, T payload) {}

    private final List<Cue<T>> pending = new ArrayList<>();
    /** Live generation per {@link #key}; absent keys get a fresh number, so retired ones never come back. */
    private final Map<Long, Long> generations = new HashMap<>();
    private long nextGeneration;
    private long tick;

    public long tick() {
        return tick;
    }

    /** Schedules {@code payload} {@code delayTicks} from now under the owner's current generation for the scope. */
    public void schedule(int owner, Scope scope, long delayTicks, T payload) {
        long generation = generations.computeIfAbsent(key(owner, scope), k -> ++nextGeneration);
        pending.add(new Cue<>(owner, scope, generation, tick + Math.max(0, delayTicks), payload));
    }

    /** Advances the clock one tick and removes the due cues; while paused, neither. */
    public List<Cue<T>> advance(boolean paused) {
        if (paused) return List.of();
        tick++;
        return drain(false);
    }

    /** Removes and returns the current cues due by now, in scheduling order; while paused, none. */
    public List<Cue<T>> drain(boolean paused) {
        if (paused || pending.isEmpty()) return List.of();
        List<Cue<T>> due = new ArrayList<>();
        for (int i = 0; i < pending.size(); ) {
            Cue<T> cue = pending.get(i);
            if (cue.dueTick > tick) {
                i++;
                continue;
            }
            pending.remove(i);
            if (current(cue)) due.add(cue);
        }
        return due;
    }

    /** Whether no cancellation or clear has retired the cue's generation since it was scheduled. */
    public boolean current(Cue<T> cue) {
        Long generation = generations.get(key(cue.owner, cue.scope));
        return generation != null && generation == cue.generation;
    }

    /** Puts a still-current in-flight cue back as due now, e.g. once its buffer finished decoding. */
    public void requeue(Cue<T> cue) {
        if (current(cue)) pending.add(new Cue<>(cue.owner, cue.scope, cue.generation, tick, cue.payload));
    }

    /** Drops the owner's pending cues of one scope and retires that scope's generation. */
    public void cancel(int owner, Scope scope) {
        pending.removeIf(cue -> cue.owner == owner && cue.scope == scope);
        generations.remove(key(owner, scope));
    }

    /** Drops all the owner's pending cues and retires all its generations. */
    public void cancelAll(int owner) {
        for (Scope scope : Scope.values()) cancel(owner, scope);
    }

    /** Drops every pending cue and retires every generation. */
    public void clear() {
        pending.clear();
        generations.clear();
    }

    private static long key(int owner, Scope scope) {
        return ((long) owner << 2) | scope.ordinal();
    }
}
