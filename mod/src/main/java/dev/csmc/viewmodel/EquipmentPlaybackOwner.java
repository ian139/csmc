package dev.csmc.viewmodel;

/**
 * The actor, hand and item whose server event started an equipment viewmodel playback. Cancellation is scoped to it,
 * so another player's cancel never stops this client's playback of the same item type.
 */
public record EquipmentPlaybackOwner<H, I>(int actor, H hand, I item) {
    /** {@code hand} null when the actor's current hands no longer reveal which one used the item. */
    public boolean cancelledBy(int actor, H hand, I item) {
        return this.actor == actor && this.item.equals(item) && (hand == null || this.hand.equals(hand));
    }
}
