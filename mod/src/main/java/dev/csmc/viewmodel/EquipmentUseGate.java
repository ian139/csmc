package dev.csmc.viewmodel;

import java.util.UUID;

/** Client input lifecycle and ownership rules for held healthshot/C4 use and defusing. */
public final class EquipmentUseGate {
    private EquipmentUseGate() {}

    /**
     * Vanilla skips its use-release handling while a screen is open, and a key held during focus loss stays down, so
     * the server would otherwise finish an inject/plant the player is no longer controlling.
     */
    public static boolean releaseHeldUse(boolean usingEquipment, boolean screenOpen, boolean windowActive) {
        return usingEquipment && (screenOpen || !windowActive);
    }

    /** Whether held use may keep renewing a defuse lease. */
    public static boolean canHoldDefuse(boolean screenOpen, boolean windowActive, boolean alive, boolean spectator,
                                        boolean useDown) {
        return !screenOpen && windowActive && alive && !spectator && useDown;
    }

    /** Only the bomb's synchronized defuser identity confirms ownership; bomb-wide progress events do not. */
    public static boolean ownsDefuse(UUID local, UUID syncedDefuser) {
        return local != null && local.equals(syncedDefuser);
    }
}
