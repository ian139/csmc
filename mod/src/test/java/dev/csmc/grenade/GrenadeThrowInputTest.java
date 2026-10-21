package dev.csmc.grenade;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GrenadeThrowInputTest {
    private static final Object GRENADE = new Object();

    private static void tick(GrenadeThrowInput input, boolean focused, boolean attack, boolean use) {
        input.tick(true, true, focused, 0, GRENADE, attack, use);
    }

    @Test
    public void spectatorsKeepVanillaInput() {
        assertTrue(GrenadeThrowInput.ownsInput(true, false));
        assertFalse(GrenadeThrowInput.ownsInput(true, true));
        assertFalse(GrenadeThrowInput.ownsInput(false, false));
    }

    @Test
    public void clickBetweenTicksPrimesAndThrows() {
        GrenadeThrowInput input = new GrenadeThrowInput();
        input.edge(GrenadeThrowInput.USE, true);
        input.edge(GrenadeThrowInput.USE, false);
        tick(input, true, false, false);
        assertTrue(input.primedThisTick());
        assertEquals(GrenadeThrow.Strength.LOB, input.thrown());
        assertFalse(input.primed());
    }

    @Test
    public void releasingBothButtonsInEitherOrderThrowsMedium() {
        for (boolean attackFirst : new boolean[] {true, false}) {
            GrenadeThrowInput input = new GrenadeThrowInput();
            input.edge(GrenadeThrowInput.ATTACK, true);
            input.edge(GrenadeThrowInput.USE, true);
            tick(input, true, true, true);
            assertTrue(input.primed());
            assertEquals(GrenadeThrow.Strength.MEDIUM, input.strength());
            input.edge(attackFirst ? GrenadeThrowInput.ATTACK : GrenadeThrowInput.USE, false);
            tick(input, true, !attackFirst, attackFirst);
            assertTrue(input.primed());
            assertEquals(GrenadeThrow.Strength.MEDIUM, input.strength());
            input.edge(attackFirst ? GrenadeThrowInput.USE : GrenadeThrowInput.ATTACK, false);
            tick(input, true, false, false);
            assertEquals(GrenadeThrow.Strength.MEDIUM, input.thrown());
        }
    }

    @Test
    public void releasingBothButtonsBetweenTicksThrowsMedium() {
        GrenadeThrowInput input = new GrenadeThrowInput();
        input.edge(GrenadeThrowInput.ATTACK, true);
        input.edge(GrenadeThrowInput.USE, true);
        tick(input, true, true, true);
        input.edge(GrenadeThrowInput.ATTACK, false);
        input.edge(GrenadeThrowInput.USE, false);
        tick(input, true, false, false);
        assertEquals(GrenadeThrow.Strength.MEDIUM, input.thrown());
    }

    @Test
    public void addingTheOtherButtonRaisesALobToMedium() {
        GrenadeThrowInput input = new GrenadeThrowInput();
        input.edge(GrenadeThrowInput.USE, true);
        tick(input, true, false, true);
        assertEquals(GrenadeThrow.Strength.LOB, input.strength());
        input.edge(GrenadeThrowInput.ATTACK, true);
        tick(input, true, true, true);
        assertEquals(GrenadeThrow.Strength.MEDIUM, input.strength());
    }

    @Test
    public void focusLossCancelsBeforeReleaseAndLatches() {
        GrenadeThrowInput input = new GrenadeThrowInput();
        input.edge(GrenadeThrowInput.ATTACK, true);
        tick(input, true, true, false);
        assertTrue(input.primed());
        input.edge(GrenadeThrowInput.ATTACK, false);
        tick(input, false, true, false);
        assertFalse(input.primed());
        assertNull(input.thrown());
        // Focus back with the button still down: no prime until it is released.
        tick(input, true, true, false);
        assertFalse(input.primed());
        input.edge(GrenadeThrowInput.ATTACK, false);
        tick(input, true, false, false);
        assertNull(input.thrown());
        input.edge(GrenadeThrowInput.ATTACK, true);
        tick(input, true, true, false);
        assertTrue(input.primed());
    }

    @Test
    public void slotChangeCancelsWithoutThrowing() {
        GrenadeThrowInput input = new GrenadeThrowInput();
        input.edge(GrenadeThrowInput.ATTACK, true);
        tick(input, true, true, false);
        input.edge(GrenadeThrowInput.ATTACK, false);
        input.tick(true, true, true, 1, GRENADE, false, false);
        assertFalse(input.primed());
        assertNull(input.thrown());
    }

    @Test
    public void releaseWithoutEdgeIsReconciled() {
        GrenadeThrowInput input = new GrenadeThrowInput();
        input.edge(GrenadeThrowInput.ATTACK, true);
        tick(input, true, true, false);
        tick(input, true, false, false);
        assertEquals(GrenadeThrow.Strength.OVERHAND, input.thrown());
    }
}
