package dev.csmc.thirdperson;

import dev.csmc.grenade.GrenadeThrow;

/**
 * Server-side, per-player grenade prime visual state, free of game classes so it can be unit tested. Driven by
 * untrusted client prime/cancel requests: a prime is accepted only while the caller reports a valid holder (alive,
 * not a spectator, grenade in the main hand) and at most {@value #MAX_PRIMES} times per {@value #WINDOW_TICKS}
 * ticks; a repeated prime of the same held slot/item keeps the original prime time. Cancellation is never rate
 * limited, so a primed state cannot go stale. A primed state ends on cancel, throw, a tick that finds the holder
 * invalid or holding a different slot/item, or {@link #forgetPrime} when the holder's entity was replaced (respawn)
 * or moved to another world. Purely visual: gameplay throws never depend on this state.
 */
public final class GrenadePrimeState {
    public static final int MAX_PRIMES = 4;
    public static final int WINDOW_TICKS = 20;

    /** What to broadcast to trackers after a transition. */
    public enum Result { NONE, PRIME, CANCEL }

    /** Server ticks of the most recent accepted primes, as a ring of {@link #filled} entries starting at {@link #head}. */
    private final long[] accepted = new long[MAX_PRIMES];
    private int head;
    private int filled;

    private boolean primed;
    private long primedAt;
    private int slot;
    private Object item;

    /** {@code PlayerActionStatePayload.GRENADE_THROW} value: 0 overhand (attack), 1 lob (use), 2 medium (both). */
    public static int throwValue(GrenadeThrow.Strength strength) {
        return switch (strength) {
            case OVERHAND -> 0;
            case LOB -> 1;
            case MEDIUM -> 2;
        };
    }

    /**
     * A client prime request at server tick {@code now}. {@code valid}: the sender may prime (alive, not a spectator,
     * main-hand grenade); {@code slot}/{@code item} identify what is held.
     */
    public Result prime(long now, boolean valid, int slot, Object item) {
        if (!valid) return Result.NONE;
        boolean same = primed && slot == this.slot && item == this.item;
        if (same) return Result.NONE;
        if (!allowed(now)) {
            // A prime of something else while still primed makes the old prime stale; never leave it showing.
            return clear() ? Result.CANCEL : Result.NONE;
        }
        accepted[(head + filled) % MAX_PRIMES] = now;
        if (filled < MAX_PRIMES) filled++;
        else head = (head + 1) % MAX_PRIMES;
        primed = true;
        primedAt = now;
        this.slot = slot;
        this.item = item;
        return Result.PRIME;
    }

    /** A client cancel request: always accepted, never rate limited. */
    public Result cancel() {
        return clear() ? Result.CANCEL : Result.NONE;
    }

    /** Per-tick revalidation of the holder; cancels when it became invalid or switched slot/item. */
    public Result tick(boolean valid, int slot, Object item) {
        if (primed && (!valid || slot != this.slot || item != this.item)) {
            clear();
            return Result.CANCEL;
        }
        return Result.NONE;
    }

    /** An accepted gameplay throw ends the prime without a cancel (the throw broadcast supersedes it). */
    public void thrown() {
        clear();
    }

    /**
     * The holder respawned or changed world: observers of the old entity already discarded its prime, so end it
     * without a cancel. The rate history is kept; the next prime, even of the same slot/item, is a fresh one.
     */
    public void forgetPrime() {
        clear();
    }

    public boolean primed() {
        return primed;
    }

    /** Server tick of the prime currently shown; meaningful only while {@link #primed()}. */
    public long primedAt() {
        return primedAt;
    }

    /** Not primed and no accepted prime still inside the rate window: the state can be discarded. */
    public boolean idle(long now) {
        return !primed && (filled == 0 || now - newest() >= WINDOW_TICKS);
    }

    private boolean allowed(long now) {
        return filled < MAX_PRIMES || now - accepted[head] >= WINDOW_TICKS;
    }

    private long newest() {
        return accepted[(head + filled - 1) % MAX_PRIMES];
    }

    private boolean clear() {
        boolean was = primed;
        primed = false;
        item = null;
        return was;
    }
}
