package dev.csmc.viewmodel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShotCorrelatorTest {
    private static final Object GLOCK = new Object();
    private static final Object AK = new Object();

    @Test
    public void lateMagazineSyncNeverReplaysAShotOrSwallowsTheNext() {
        ShotCorrelator shots = new ShotCorrelator();
        assertTrue(shots.server(GLOCK, 0, 1, 19));          // shot A
        assertFalse(shots.magazineDrop(GLOCK, 0, 19));     // A's magazine sync, however late
        assertTrue(shots.server(GLOCK, 0, 2, 18));          // shot B right after: still its own shot
        assertFalse(shots.magazineDrop(GLOCK, 0, 18));
    }

    @Test
    public void magazineFallbackPlaysOnceAndAbsorbsTheLateServerEvent() {
        ShotCorrelator shots = new ShotCorrelator();
        assertTrue(shots.magazineDrop(GLOCK, 0, 19));
        assertFalse(shots.server(GLOCK, 0, 1, 19));
        assertTrue(shots.server(GLOCK, 0, 2, 18));
    }

    @Test
    public void oneSyncCoveringTwoShotsDiscardsTheOlderShot() {
        ShotCorrelator shots = new ShotCorrelator();
        assertTrue(shots.server(GLOCK, 0, 1, 19));
        assertTrue(shots.server(GLOCK, 0, 2, 18));
        assertFalse(shots.magazineDrop(GLOCK, 0, 18));
        shots.magazineRaised(GLOCK, 0); // reload back to 20
        // A new magazine's 19 is a new shot, not the stale first one.
        assertTrue(shots.magazineDrop(GLOCK, 0, 19));
    }

    @Test
    public void repeatedOrOlderServerSequencesAreIgnored() {
        ShotCorrelator shots = new ShotCorrelator();
        assertTrue(shots.server(GLOCK, 0, 5, 10));
        assertFalse(shots.server(GLOCK, 0, 5, 10));
        assertFalse(shots.server(GLOCK, 0, 4, 11));
    }

    @Test
    public void bindingChangeDropsUnmatchedState() {
        ShotCorrelator shots = new ShotCorrelator();
        assertTrue(shots.server(GLOCK, 0, 1, 19));
        // Switching to an AK whose magazine happens to drop to 19 is not the Glock's shot.
        assertTrue(shots.magazineDrop(AK, 1, 19));
        // Same item in another slot is another binding too.
        assertTrue(shots.magazineDrop(AK, 2, 19));
        // The sequence is per player: switching guns keeps rejecting old shots.
        assertFalse(shots.server(AK, 2, 1, 29));
    }

    @Test
    public void resetStartsANewSession() {
        ShotCorrelator shots = new ShotCorrelator();
        assertTrue(shots.server(GLOCK, 0, 9, 19));
        shots.reset();
        // The server restarts its sequence on a new session.
        assertTrue(shots.server(GLOCK, 0, 1, 19));
    }
}
