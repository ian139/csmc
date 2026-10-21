package dev.csmc.melee;

/**
 * Client knife input, free of client classes so it can be unit tested. Attack/use press edges are queued in
 * arrival order (a click that lands entirely between two ticks still counts) and drained once per client tick into
 * actions: attack press = {@link #SLASH}, use press = {@link #STAB}. Holding a button never repeats; the server owns
 * cooldowns. Edges are dropped when the tick finds no usable knife (not held, screen open, spectator, dead) or the
 * window unfocused, so a click on a menu or another item never slashes later.
 */
public final class KnifeInput {
    /** Wire values of {@code KnifeActionPayload.action}. */
    public static final byte SLASH = 0;
    public static final byte STAB = 1;
    public static final int ATTACK = 0;
    public static final int USE = 1;
    /** Most actions one tick can emit; extra presses within a single tick are ignored. */
    public static final int CAPACITY = 8;

    private final byte[] queue = new byte[CAPACITY];
    private int queued;
    private boolean attack;
    private boolean use;

    /** Whether a main-hand knife takes over attack/use (no punching, mining or item use). */
    public static boolean ownsInput(boolean holdingKnife, boolean spectator) {
        return holdingKnife && !spectator;
    }

    /** A key state change in arrival order; only up→down transitions queue an action. */
    public void edge(int button, boolean down) {
        boolean wasDown = button == USE ? use : attack;
        if (button == USE) use = down;
        else attack = down;
        if (down && !wasDown && queued < CAPACITY) queue[queued++] = button == USE ? STAB : SLASH;
    }

    /**
     * One client tick: copies this tick's actions into {@code out} and clears the queue.
     *
     * @param holding a knife is in the main hand, no screen is open and the player is alive and not a spectator
     * @param focused the game window has input focus
     * @param attackDown polled attack state (reconciles releases vanilla applies without an edge)
     * @param useDown polled use state
     * @return the number of actions written to {@code out}
     */
    public int tick(boolean holding, boolean focused, boolean attackDown, boolean useDown, byte[] out) {
        int count = holding && focused ? Math.min(queued, out.length) : 0;
        System.arraycopy(queue, 0, out, 0, count);
        queued = 0;
        attack = attackDown;
        use = useDown;
        return count;
    }

    public void reset() {
        queued = 0;
        attack = use = false;
    }
}
