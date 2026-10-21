package dev.csmc.viewmodel;

/**
 * Matches the local player's two reports of the same shot so each shot starts exactly one fire playback: the
 * server's FIRE event (shot sequence and post-shot magazine) and the synced magazine dropping on the held stack
 * (the fallback when an event is missing). Shots are keyed by post-shot magazine within one weapon binding (held
 * item and slot), never by arrival time, so a late sync cannot swallow the next shot or replay its own. A sync
 * showing magazine {@code m} also settles every unmatched report of a shot that left more than {@code m}.
 * Changing binding drops unmatched reports; a raised magazine (reload) starts a fresh count; {@link #reset} starts a
 * new session. Fixed capacity, allocation-free; render/client thread only.
 */
public final class ShotCorrelator {
    private static final int CAPACITY = 8;

    private final int[] server = new int[CAPACITY];
    private final int[] fallback = new int[CAPACITY];
    private int serverCount;
    private int fallbackCount;
    private Object item;
    private int slot = -1;
    private int lastSequence;

    /** New session: the server's shot sequence restarts too. */
    public void reset() {
        item = null;
        slot = -1;
        lastSequence = 0;
        serverCount = 0;
        fallbackCount = 0;
    }

    /**
     * A server FIRE event (shot {@code sequence}, leaving {@code magazine}) for the held binding. True when it is a
     * new shot whose playback should start; false for a repeated event or one the magazine fallback already played.
     */
    public boolean server(Object item, int slot, int sequence, int magazine) {
        if (sequence <= lastSequence) return false;
        lastSequence = sequence;
        bind(item, slot);
        if (settle(fallback, fallbackCount, magazine)) {
            fallbackCount = remaining;
            return false;
        }
        fallbackCount = remaining;
        serverCount = push(server, serverCount, magazine);
        return true;
    }

    /**
     * The held binding's synced magazine dropped to {@code magazine}. True when no server event covered this shot,
     * so the fallback playback should start.
     */
    public boolean magazineDrop(Object item, int slot, int magazine) {
        bind(item, slot);
        if (settle(server, serverCount, magazine)) {
            serverCount = remaining;
            return false;
        }
        serverCount = remaining;
        fallbackCount = push(fallback, fallbackCount, magazine);
        return true;
    }

    /** The held binding's magazine went up (reload): earlier post-shot counts can recur and must not match. */
    public void magazineRaised(Object item, int slot) {
        bind(item, slot);
        serverCount = 0;
        fallbackCount = 0;
    }

    private void bind(Object item, int slot) {
        if (item == this.item && slot == this.slot) return;
        this.item = item;
        this.slot = slot;
        serverCount = 0;
        fallbackCount = 0;
    }

    /** Count left in the array by the last {@link #settle}. */
    private int remaining;

    /**
     * Removes {@code magazine} and every entry above it (older shots of this magazine, settled by this report) from
     * {@code values}; true when {@code magazine} itself was present.
     */
    private boolean settle(int[] values, int count, int magazine) {
        boolean found = false;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (values[i] == magazine) found = true;
            if (values[i] < magazine) values[kept++] = values[i];
        }
        remaining = kept;
        return found;
    }

    private static int push(int[] values, int count, int magazine) {
        if (count == CAPACITY) {
            System.arraycopy(values, 1, values, 0, CAPACITY - 1);
            count--;
        }
        values[count] = magazine;
        return count + 1;
    }
}
