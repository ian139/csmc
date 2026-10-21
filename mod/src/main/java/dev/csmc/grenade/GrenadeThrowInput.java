package dev.csmc.grenade;

/**
 * Client prime-and-release state machine, free of client classes so it can be unit tested. Attack/use key
 * edges are queued in arrival order (a click that lands entirely between two ticks still primes and throws) and
 * processed once per client tick, after any cancellation for that tick. Cancelling (lost hold, slot/item change,
 * lost window focus) arms a latch: both buttons must be released before the next prime.
 */
public final class GrenadeThrowInput {
    public static final int ATTACK = 0;
    public static final int USE = 1;
    private static final int CAPACITY = 32;

    private final byte[] edges = new byte[CAPACITY];
    private int edgeCount;
    private boolean queuedAttack;
    private boolean queuedUse;

    private boolean attack;
    private boolean use;
    private boolean primed;
    private boolean latch;
    private boolean heldAttack;
    private boolean heldUse;
    private int slot = -1;
    private Object item;

    private boolean holding;
    private boolean canPrime;
    private int tickSlot;
    private Object tickItem;
    private boolean primedThisTick;
    private GrenadeThrow.Strength thrown;

    /** Whether a main-hand grenade takes over attack/use (no punching, mining or item use). */
    public static boolean ownsInput(boolean holdingGrenade, boolean spectator) {
        return holdingGrenade && !spectator;
    }

    /** A key state change in arrival order; repeats of the current state are ignored. */
    public void edge(int button, boolean down) {
        boolean current = button == USE ? queuedUse : queuedAttack;
        if (current == down || edgeCount == CAPACITY) return;
        if (button == USE) queuedUse = down;
        else queuedAttack = down;
        edges[edgeCount++] = (byte) (button << 1 | (down ? 1 : 0));
    }

    /**
     * One client tick.
     *
     * @param holding a grenade is in the main hand, no screen is open and the player is alive and not a spectator
     * @param canPrime the shared grenade cooldown has expired
     * @param focused the game window has input focus
     * @param attackDown polled attack state (reconciles releases vanilla applies without an edge)
     * @param useDown polled use state
     */
    public void tick(boolean holding, boolean canPrime, boolean focused, int slot, Object item, boolean attackDown,
            boolean useDown) {
        this.holding = holding;
        this.canPrime = canPrime;
        this.tickSlot = slot;
        this.tickItem = item;
        primedThisTick = false;
        thrown = null;
        if (primed && (!holding || !focused || slot != this.slot || item != this.item)) {
            primed = false;
            latch = true;
        }
        if (!focused) {
            edgeCount = 0;
            set(attackDown, useDown);
            if (attack || use) latch = true;
            return;
        }
        for (int i = 0; i < edgeCount; i++) {
            int edge = edges[i];
            boolean down = (edge & 1) != 0;
            if (edge >> 1 == USE) use = down;
            else attack = down;
            transition();
        }
        edgeCount = 0;
        set(attackDown, useDown);
        transition();
    }

    private void set(boolean attackDown, boolean useDown) {
        attack = attackDown;
        use = useDown;
        queuedAttack = attackDown;
        queuedUse = useDown;
    }

    private void transition() {
        if (!attack && !use) latch = false;
        if (primed) {
            // A press re-aims the throw at the buttons now held; releasing one of two buttons keeps the
            // combination, so releasing both (in either order, even between ticks) still throws MEDIUM.
            if ((attack && !heldAttack) || (use && !heldUse)) {
                heldAttack = attack;
                heldUse = use;
            } else if (attack || use) {
                return;
            } else {
                primed = false;
                thrown = GrenadeThrow.Strength.of(heldAttack, heldUse);
            }
        } else if (holding && canPrime && !latch && thrown == null && (attack || use)) {
            primed = true;
            primedThisTick = true;
            heldAttack = attack;
            heldUse = use;
            slot = tickSlot;
            item = tickItem;
        }
    }

    public boolean primed() {
        return primed;
    }

    /** Primed during the last tick (pin sound). */
    public boolean primedThisTick() {
        return primedThisTick;
    }

    /** Strength thrown during the last tick, or null. */
    public GrenadeThrow.Strength thrown() {
        return thrown;
    }

    /** Buttons currently driving the primed throw. */
    public GrenadeThrow.Strength strength() {
        return GrenadeThrow.Strength.of(heldAttack, heldUse);
    }

    public void reset() {
        edgeCount = 0;
        primed = false;
        latch = false;
        attack = use = queuedAttack = queuedUse = false;
        item = null;
        slot = -1;
        thrown = null;
        primedThisTick = false;
    }
}
