package dev.csmc.viewmodel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import org.junit.Test;

public class EquipmentUseGateTest {
    private static final UUID LOCAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    public void heldEquipmentIsReleasedWhenAScreenOpens() {
        assertTrue(EquipmentUseGate.releaseHeldUse(true, true, true));
    }

    @Test
    public void heldEquipmentIsReleasedOnFocusLoss() {
        assertTrue(EquipmentUseGate.releaseHeldUse(true, false, false));
    }

    @Test
    public void focusedUseWithoutScreenIsKept() {
        assertFalse(EquipmentUseGate.releaseHeldUse(true, false, true));
    }

    @Test
    public void nothingIsReleasedWhenNotUsingEquipment() {
        assertFalse(EquipmentUseGate.releaseHeldUse(false, true, false));
    }

    @Test
    public void defuseRequiresFocusedWindowWithoutScreen() {
        assertTrue(EquipmentUseGate.canHoldDefuse(false, true, true, false, true));
        assertFalse(EquipmentUseGate.canHoldDefuse(false, false, true, false, true));
        assertFalse(EquipmentUseGate.canHoldDefuse(true, true, true, false, true));
    }

    @Test
    public void defuseRequiresLivingNonSpectatorHoldingUse() {
        assertFalse(EquipmentUseGate.canHoldDefuse(false, true, false, false, true));
        assertFalse(EquipmentUseGate.canHoldDefuse(false, true, true, true, true));
        assertFalse(EquipmentUseGate.canHoldDefuse(false, true, true, false, false));
    }

    @Test
    public void onlyTheSyncedDefuserOwnsTheDefuse() {
        assertTrue(EquipmentUseGate.ownsDefuse(LOCAL, UUID.fromString(LOCAL.toString())));
        assertFalse(EquipmentUseGate.ownsDefuse(LOCAL, OTHER));
    }

    @Test
    public void bombProgressWithoutSyncedDefuserConfirmsNobody() {
        assertFalse(EquipmentUseGate.ownsDefuse(LOCAL, null));
        assertFalse(EquipmentUseGate.ownsDefuse(null, null));
    }
}
