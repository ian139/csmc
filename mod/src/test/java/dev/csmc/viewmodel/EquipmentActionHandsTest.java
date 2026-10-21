package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EquipmentActionHandsTest {
    private static final String MAIN = "main";
    private static final String OFF = "off";
    private static final Object HEALTHSHOT = new Object();
    private static final Object C4 = new Object();
    private static final int ACTOR = 1;
    private static final int OTHER = 2;

    @Test
    public void cancelUsesTheHandRecordedAtStartNotTheCurrentInventory() {
        // Main-hand injection while the off hand holds another healthshot; the main hand then switches away.
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        assertEquals(MAIN, hands.start(ACTOR, HEALTHSHOT, MAIN));
        assertEquals(MAIN, hands.end(ACTOR, HEALTHSHOT));
    }

    @Test
    public void progressKeepsTheStartHandEvenWhenRediscoveryDiffers() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(ACTOR, HEALTHSHOT, MAIN);
        assertEquals(MAIN, hands.progress(ACTOR, HEALTHSHOT, OFF));
    }

    @Test
    public void progressWithoutStartRecordsTheDiscoveredHand() {
        // A start event can be missed (joined or entity tracked mid-action).
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        assertEquals(OFF, hands.progress(ACTOR, C4, OFF));
        assertEquals(OFF, hands.end(ACTOR, C4));
    }

    @Test
    public void unknownActionEndsWithUnknownHand() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        assertNull(hands.end(ACTOR, HEALTHSHOT));
        assertNull(hands.progress(ACTOR, HEALTHSHOT, null));
    }

    @Test
    public void endForgetsTheAction() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(ACTOR, HEALTHSHOT, MAIN);
        hands.end(ACTOR, HEALTHSHOT);
        assertNull(hands.end(ACTOR, HEALTHSHOT));
    }

    @Test
    public void actionsAreScopedToActorAndItem() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(ACTOR, HEALTHSHOT, MAIN);
        hands.start(OTHER, HEALTHSHOT, OFF);
        hands.start(ACTOR, C4, OFF);
        assertEquals(OFF, hands.end(OTHER, HEALTHSHOT));
        assertEquals(OFF, hands.end(ACTOR, C4));
        assertEquals(MAIN, hands.end(ACTOR, HEALTHSHOT));
    }

    @Test
    public void restartReplacesTheRecordedHand() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(ACTOR, HEALTHSHOT, MAIN);
        hands.start(ACTOR, HEALTHSHOT, OFF);
        assertEquals(OFF, hands.end(ACTOR, HEALTHSHOT));
    }

    @Test
    public void clearForgetsEveryAction() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(ACTOR, HEALTHSHOT, MAIN);
        hands.clear();
        assertNull(hands.end(ACTOR, HEALTHSHOT));
    }

    @Test
    public void mainHandSwitchKeepsARunningOffHandAction() {
        // Off-hand inject continues on the server across main-hand slot switches.
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(ACTOR, HEALTHSHOT, OFF);
        assertTrue(hands.running(ACTOR, OFF));
        assertFalse(hands.running(ACTOR, MAIN));
    }

    @Test
    public void mainHandSwitchInterruptsAMainHandAction() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(ACTOR, C4, MAIN);
        assertFalse(hands.running(ACTOR, OFF));
    }

    @Test
    public void runningIsScopedToTheActorAndForgottenOnEnd() {
        EquipmentActionHands<String, Object> hands = new EquipmentActionHands<>();
        hands.start(OTHER, HEALTHSHOT, OFF);
        assertFalse(hands.running(ACTOR, OFF));
        hands.end(OTHER, HEALTHSHOT);
        assertFalse(hands.running(OTHER, OFF));
    }
}
