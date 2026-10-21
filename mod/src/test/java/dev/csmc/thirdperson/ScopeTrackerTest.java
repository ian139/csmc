package dev.csmc.thirdperson;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import org.junit.Test;

public class ScopeTrackerTest {
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);

    @Test
    public void unscopedPlayerPublishesNothing() {
        ScopeTracker.Levels levels = new ScopeTracker.Levels();
        assertFalse(levels.update(alice, 0));
        assertFalse(levels.update(alice, 0));
    }

    @Test
    public void eachLevelChangePublishesOnce() {
        ScopeTracker.Levels levels = new ScopeTracker.Levels();
        assertTrue(levels.update(alice, 1));
        assertFalse(levels.update(alice, 1));
        assertTrue(levels.update(alice, 2));
        assertFalse(levels.update(alice, 2));
        assertTrue(levels.update(alice, 0));
        assertFalse(levels.update(alice, 0));
    }

    @Test
    public void playersAreTrackedIndependently() {
        ScopeTracker.Levels levels = new ScopeTracker.Levels();
        assertTrue(levels.update(alice, 1));
        assertFalse(levels.update(bob, 0));
        assertTrue(levels.update(bob, 1));
        assertFalse(levels.update(alice, 1));
    }

    @Test
    public void forgottenPlayerRestartsUnscoped() {
        ScopeTracker.Levels levels = new ScopeTracker.Levels();
        levels.update(alice, 2);
        levels.update(bob, 1);
        levels.forget(alice);
        assertFalse(levels.update(alice, 0));
        assertTrue(levels.update(alice, 2));
        assertFalse(levels.update(bob, 1));
    }

    @Test
    public void clearedStateDoesNotLeakIntoNextServer() {
        ScopeTracker.Levels levels = new ScopeTracker.Levels();
        levels.update(alice, 1);
        levels.clear();
        assertTrue(levels.update(alice, 1));
        levels.clear();
        assertFalse(levels.update(alice, 0));
    }
}
