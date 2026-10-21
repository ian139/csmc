package dev.csmc.viewmodel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EquipmentPlaybackOwnerTest {
    private static final Object MAIN = new Object();
    private static final Object OFF = new Object();
    private static final Object C4 = new Object();
    private static final Object HEALTHSHOT = new Object();
    private static final int LOCAL = 1;
    private static final int REMOTE = 2;

    @Test
    public void remoteCancelLeavesLocalPlaybackOfTheSameItem() {
        EquipmentPlaybackOwner<Object, Object> local = new EquipmentPlaybackOwner<>(LOCAL, MAIN, C4);
        assertFalse(local.cancelledBy(REMOTE, MAIN, C4));
        assertFalse(local.cancelledBy(REMOTE, null, C4));
    }

    @Test
    public void actorCancelStopsItsOwnPlaybackInThatHand() {
        EquipmentPlaybackOwner<Object, Object> remote = new EquipmentPlaybackOwner<>(REMOTE, MAIN, C4);
        assertTrue(remote.cancelledBy(REMOTE, MAIN, C4));
        assertFalse(remote.cancelledBy(REMOTE, OFF, C4));
    }

    @Test
    public void unknownHandCancelsEveryHandOfThatActor() {
        // A consumed or already replaced hand stack no longer reveals which hand used it.
        assertTrue(new EquipmentPlaybackOwner<>(REMOTE, OFF, C4).cancelledBy(REMOTE, null, C4));
        assertTrue(new EquipmentPlaybackOwner<>(REMOTE, MAIN, C4).cancelledBy(REMOTE, null, C4));
    }

    @Test
    public void cancelIsScopedToTheItem() {
        assertFalse(new EquipmentPlaybackOwner<>(LOCAL, MAIN, HEALTHSHOT).cancelledBy(LOCAL, MAIN, C4));
    }
}
