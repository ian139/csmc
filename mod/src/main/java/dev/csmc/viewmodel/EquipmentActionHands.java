package dev.csmc.viewmodel;

import java.util.HashMap;
import java.util.Map;

/**
 * The hand each actor's server-confirmed equipment action started in. Cancel and completion resolve the playback's
 * owner from here rather than the actor's current inventory, which may by then hold the same item in the other hand.
 */
public final class EquipmentActionHands<H, I> {
    private record Action(int actor, Object item) { }

    private final Map<Action, H> hands = new HashMap<>();

    /** Records {@code hand} (null when unknown) for a newly started action and returns it. */
    public H start(int actor, I item, H hand) {
        if (hand == null) hands.remove(new Action(actor, item));
        else hands.put(new Action(actor, item), hand);
        return hand;
    }

    /** The start hand; without a recorded start, records and returns {@code discovered}. */
    public H progress(int actor, I item, H discovered) {
        H recorded = hands.get(new Action(actor, item));
        return recorded != null ? recorded : start(actor, item, discovered);
    }

    /** Forgets the action and returns its start hand, or null when it is unknown. */
    public H end(int actor, I item) {
        return hands.remove(new Action(actor, item));
    }

    /** Whether {@code actor} has a server-confirmed action running in {@code hand}. */
    public boolean running(int actor, H hand) {
        for (Map.Entry<Action, H> entry : hands.entrySet()) {
            if (entry.getKey().actor == actor && entry.getValue().equals(hand)) return true;
        }
        return false;
    }

    public void clear() {
        hands.clear();
    }
}
