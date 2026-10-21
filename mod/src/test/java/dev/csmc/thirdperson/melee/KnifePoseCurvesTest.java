package dev.csmc.thirdperson.melee;

import static org.junit.Assert.*;
import dev.csmc.thirdperson.melee.KnifePoseCurves.Action;
import dev.csmc.thirdperson.melee.KnifePoseCurves.Pose;
import org.junit.Test;

public class KnifePoseCurvesTest {
    private static Pose sample(Action action, double progress) {
        Pose out = new Pose();
        double duration = KnifePoseCurves.duration(action);
        KnifePoseCurves.sample(action, progress * duration, duration, 0, false, out);
        return out;
    }

    @Test public void acceptedEventsSelectTheCorrectSilhouette() {
        assertEquals(Action.DRAW, KnifePoseCurves.action("deploy"));
        assertEquals(Action.INSPECT, KnifePoseCurves.action("inspect"));
        assertEquals(Action.SLASH, KnifePoseCurves.action("slash"));
        assertEquals(Action.SLASH, KnifePoseCurves.action("slash_backstab"));
        assertEquals(Action.SLASH, KnifePoseCurves.action("slash_hit"));
        assertEquals(Action.STAB, KnifePoseCurves.action("stab_hit"));
        assertEquals(Action.BACKSTAB, KnifePoseCurves.action("backstab"));
        assertEquals(Action.IDLE, KnifePoseCurves.action("hit"));
    }

    @Test public void drawRisesFromLoweredArmIntoReadyGrip() {
        assertTrue(sample(Action.DRAW, 0).rightArmX > sample(Action.IDLE, 0).rightArmX);
        assertPoseEquals(sample(Action.IDLE, 0), sample(Action.DRAW, 1));
    }

    @Test public void slashSweepsAcrossBodyRatherThanThrusting() {
        Pose windup = sample(Action.SLASH, 0.22);
        Pose cross = sample(Action.SLASH, 0.56);
        assertTrue(windup.rightArmY < -0.9F);
        assertTrue(cross.rightArmY > 0.9F);
        assertTrue(windup.rightArmZ > cross.rightArmZ);
    }

    @Test public void stabDrawsBackThenDrivesStraightForward() {
        Pose idle = sample(Action.IDLE, 0);
        Pose windup = sample(Action.STAB, 0.25);
        Pose thrust = sample(Action.STAB, 0.48);
        assertTrue(windup.rightArmX > idle.rightArmX);
        assertTrue(thrust.rightArmX < -1.45F);
        assertTrue(Math.abs(thrust.rightArmY - idle.rightArmY) < 0.2F);
    }

    @Test public void backstabHasHeavierReachAndLongerRecovery() {
        assertTrue(sample(Action.BACKSTAB, 0.48).rightArmX < sample(Action.STAB, 0.48).rightArmX);
        assertTrue(KnifePoseCurves.duration(Action.BACKSTAB) > KnifePoseCurves.duration(Action.STAB));
    }

    @Test public void inspectRaisesBladeAndPresentsItAcrossChest() {
        Pose idle = sample(Action.IDLE, 0);
        Pose inspect = sample(Action.INSPECT, 0.4);
        assertTrue(inspect.rightArmX < idle.rightArmX - 0.6F);
        assertTrue(inspect.rightArmY < idle.rightArmY - 0.5F);
        assertPoseEquals(idle, sample(Action.INSPECT, 1));
    }

    @Test public void expirationAndCancellationReturnExactlyToIdle() {
        Pose idle = sample(Action.IDLE, 0);
        for (Action action : Action.values()) {
            assertPoseEquals(idle, sample(action, 1));
            assertPoseEquals(idle, sample(action, 2));
        }
        Pose cancelled = new Pose();
        KnifePoseCurves.sample(Action.IDLE, 0, 0, 0, false, cancelled);
        assertPoseEquals(idle, cancelled);
    }

    @Test public void everyActionIsContinuousAtRecoveryEndpoint() {
        Pose idle = sample(Action.IDLE, 0);
        for (Action action : Action.values()) {
            Pose almost = sample(action, 0.99999);
            assertEquals(idle.rightArmX, almost.rightArmX, 0.001F);
            assertEquals(idle.rightArmY, almost.rightArmY, 0.001F);
            assertEquals(idle.rightArmZ, almost.rightArmZ, 0.001F);
        }
    }

    @Test public void dualDaggersMirrorBothArmsDuringEveryAction() {
        for (Action action : Action.values()) {
            Pose out = new Pose();
            double duration = KnifePoseCurves.duration(action);
            KnifePoseCurves.sample(action, duration * 0.48, duration, 0.2F, true, out);
            assertEquals(out.rightArmX, out.leftArmX, 0);
            assertEquals(out.rightArmY, -out.leftArmY, 0);
            assertEquals(out.rightArmZ, -out.leftArmZ, 0);
        }
    }

    @Test public void pitchFollowsAimWithoutChangingActionSweep() {
        Pose flat = new Pose();
        Pose up = new Pose();
        KnifePoseCurves.sample(Action.SLASH, 0.2, 0.4, 0, false, flat);
        KnifePoseCurves.sample(Action.SLASH, 0.2, 0.4, -0.6F, false, up);
        assertEquals(-0.33F, up.rightArmX - flat.rightArmX, 0.0001F);
        assertEquals(flat.rightArmY, up.rightArmY, 0);
    }

    private static void assertPoseEquals(Pose expected, Pose actual) {
        assertEquals(expected.rightArmX, actual.rightArmX, 0.0001F);
        assertEquals(expected.rightArmY, actual.rightArmY, 0.0001F);
        assertEquals(expected.rightArmZ, actual.rightArmZ, 0.0001F);
        assertEquals(expected.leftArmX, actual.leftArmX, 0.0001F);
        assertEquals(expected.leftArmY, actual.leftArmY, 0.0001F);
        assertEquals(expected.leftArmZ, actual.leftArmZ, 0.0001F);
    }
}
