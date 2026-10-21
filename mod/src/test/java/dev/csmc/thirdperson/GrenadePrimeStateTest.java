package dev.csmc.thirdperson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.csmc.grenade.GrenadeThrow;
import dev.csmc.thirdperson.GrenadePrimeState.Result;
import org.junit.Test;

public class GrenadePrimeStateTest {
    private static final Object HE = new Object();
    private static final Object FLASH = new Object();

    @Test
    public void invalidHolderCannotPrime() {
        GrenadePrimeState state = new GrenadePrimeState();
        assertEquals(Result.NONE, state.prime(0, false, 0, HE));
        assertFalse(state.primed());
        assertTrue(state.idle(0));
    }

    @Test
    public void validPrimeIsBroadcastOnce() {
        GrenadePrimeState state = new GrenadePrimeState();
        assertEquals(Result.PRIME, state.prime(5, true, 2, HE));
        assertTrue(state.primed());
        assertEquals(5, state.primedAt());
    }

    @Test
    public void duplicatePrimeKeepsOriginalTimeAndIsNotRebroadcast() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(5, true, 2, HE);
        assertEquals(Result.NONE, state.prime(9, true, 2, HE));
        assertEquals(Result.NONE, state.prime(400, true, 2, HE));
        assertEquals(5, state.primedAt());
        assertTrue(state.primed());
    }

    @Test
    public void rapidPrimesAreBoundedPerWindow() {
        GrenadePrimeState state = new GrenadePrimeState();
        int accepted = 0;
        for (int tick = 0; tick < GrenadePrimeState.WINDOW_TICKS; tick++) {
            if (state.prime(tick, true, 0, HE) == Result.PRIME) accepted++;
            state.cancel();
        }
        assertEquals(GrenadePrimeState.MAX_PRIMES, accepted);
        assertEquals(Result.PRIME, state.prime(GrenadePrimeState.WINDOW_TICKS, true, 0, HE));
    }

    @Test
    public void rateIsASlidingWindow() {
        GrenadePrimeState state = new GrenadePrimeState();
        for (int i = 0; i < GrenadePrimeState.MAX_PRIMES; i++) {
            assertEquals(Result.PRIME, state.prime(i * 2L, true, 0, HE));
            assertEquals(Result.CANCEL, state.cancel());
        }
        // The oldest accepted prime was at tick 0, so tick 19 is still inside the window and tick 20 is not.
        assertEquals(Result.NONE, state.prime(19, true, 0, HE));
        assertEquals(Result.PRIME, state.prime(20, true, 0, HE));
        assertEquals(Result.CANCEL, state.cancel());
        assertEquals(Result.NONE, state.prime(21, true, 0, HE));
        assertEquals(Result.PRIME, state.prime(22, true, 0, HE));
    }

    @Test
    public void cancellationIsNeverRateLimited() {
        GrenadePrimeState state = new GrenadePrimeState();
        for (int i = 0; i < GrenadePrimeState.MAX_PRIMES; i++) {
            state.prime(0, true, 0, HE);
            assertEquals(Result.CANCEL, state.cancel());
        }
        assertEquals(Result.NONE, state.prime(1, true, 0, HE));
        assertEquals(Result.NONE, state.cancel());
        assertFalse(state.primed());
    }

    @Test
    public void cancelWithoutPrimeIsIgnored() {
        GrenadePrimeState state = new GrenadePrimeState();
        assertEquals(Result.NONE, state.cancel());
        state.prime(0, true, 0, HE);
        assertEquals(Result.CANCEL, state.cancel());
        assertEquals(Result.NONE, state.cancel());
    }

    @Test
    public void tickCancelsOnSlotChange() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(0, true, 0, HE);
        assertEquals(Result.NONE, state.tick(true, 0, HE));
        assertEquals(Result.CANCEL, state.tick(true, 1, HE));
        assertFalse(state.primed());
        assertEquals(Result.NONE, state.tick(true, 1, HE));
    }

    @Test
    public void tickCancelsOnItemChange() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(0, true, 0, HE);
        assertEquals(Result.CANCEL, state.tick(true, 0, FLASH));
        assertFalse(state.primed());
    }

    @Test
    public void tickCancelsOnDeathOrSpectator() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(0, true, 0, HE);
        assertEquals(Result.CANCEL, state.tick(false, 0, HE));
        assertFalse(state.primed());
    }

    @Test
    public void primingSomethingElseReprimes() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(0, true, 0, HE);
        assertEquals(Result.PRIME, state.prime(3, true, 1, FLASH));
        assertEquals(3, state.primedAt());
        assertEquals(Result.NONE, state.tick(true, 1, FLASH));
    }

    @Test
    public void rateLimitedPrimeOfSomethingElseClearsStalePrime() {
        GrenadePrimeState state = new GrenadePrimeState();
        for (int i = 0; i < GrenadePrimeState.MAX_PRIMES - 1; i++) {
            state.prime(0, true, 0, HE);
            state.cancel();
        }
        state.prime(0, true, 0, HE);
        assertEquals(Result.CANCEL, state.prime(1, true, 1, FLASH));
        assertFalse(state.primed());
    }

    @Test
    public void throwEndsPrimeWithoutLaterCancel() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(0, true, 0, HE);
        state.thrown();
        assertFalse(state.primed());
        // The last grenade left the hand: no cancel may override the throw.
        assertEquals(Result.NONE, state.tick(false, 0, null));
        assertEquals(Result.NONE, state.cancel());
    }

    @Test
    public void worldChangeOrRespawnAllowsFreshSameSlotPrime() {
        GrenadePrimeState state = new GrenadePrimeState();
        assertEquals(Result.PRIME, state.prime(0, true, 3, HE));
        state.forgetPrime();
        assertFalse(state.primed());
        // The client dropped its prime silently; the same slot/item is a new prime, not a duplicate.
        assertEquals(Result.PRIME, state.prime(5, true, 3, HE));
        assertTrue(state.primed());
        assertEquals(5, state.primedAt());
    }

    @Test
    public void forgottenPrimeIsNeverCancelledLater() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(0, true, 3, HE);
        state.forgetPrime();
        // Observers already discarded the old entity's prime; nothing may reach the new world's observers.
        assertEquals(Result.NONE, state.tick(true, 4, FLASH));
        assertEquals(Result.NONE, state.tick(false, 3, HE));
        assertEquals(Result.NONE, state.cancel());
    }

    @Test
    public void forgettingPrimeKeepsRateWindow() {
        GrenadePrimeState state = new GrenadePrimeState();
        for (int i = 0; i < GrenadePrimeState.MAX_PRIMES; i++) {
            assertEquals(Result.PRIME, state.prime(i, true, 0, HE));
            state.forgetPrime();
        }
        assertEquals(Result.NONE, state.prime(GrenadePrimeState.MAX_PRIMES, true, 0, HE));
        assertFalse(state.primed());
        assertEquals(Result.PRIME, state.prime(GrenadePrimeState.WINDOW_TICKS, true, 0, HE));
    }

    @Test
    public void stateBecomesIdleOnceUnprimedAndOutsideWindow() {
        GrenadePrimeState state = new GrenadePrimeState();
        state.prime(10, true, 0, HE);
        assertFalse(state.idle(100));
        state.tick(true, 1, HE);
        assertFalse(state.idle(10 + GrenadePrimeState.WINDOW_TICKS - 1));
        assertTrue(state.idle(10 + GrenadePrimeState.WINDOW_TICKS));
    }

    @Test
    public void throwValuesMatchPayloadStrengthCodes() {
        assertEquals(0, GrenadePrimeState.throwValue(GrenadeThrow.Strength.OVERHAND));
        assertEquals(1, GrenadePrimeState.throwValue(GrenadeThrow.Strength.LOB));
        assertEquals(2, GrenadePrimeState.throwValue(GrenadeThrow.Strength.MEDIUM));
    }
}
