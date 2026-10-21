package dev.csmc.thirdperson.blend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behaviour of the per-entity critically damped pose spring (angular frequency 20 rad/s): vanilla seeding on first
 * sight, exact step-size-invariant integration, no overshoot, decay on provider disappearance, timestamp hygiene.
 */
public class PoseSmootherTest {
    private static final double OMEGA = 20.0;
    private static final float EPS = 1e-4F;
    private static final double FRAME = 1.0 / 60.0;

    /** Fraction of the initial gap remaining after {@code dt} seconds for a critically damped spring released at rest. */
    private static double restDecay(double dt) {
        return (1.0 + OMEGA * dt) * Math.exp(-OMEGA * dt);
    }

    private static PoseFrame vanilla(float rightX, float leftX) {
        PoseFrame p = new PoseFrame();
        p.rightArmX = rightX;
        p.leftArmX = leftX;
        p.rightArmY = 0.1F;
        p.leftArmY = -0.1F;
        p.rightArmZ = 0.05F;
        p.leftArmZ = -0.05F;
        return p;
    }

    private static PoseFrame aim(float rightX, float leftX, float weight) {
        PoseFrame p = new PoseFrame();
        p.rightArmX = rightX;
        p.leftArmX = leftX;
        p.rightArmY = -0.3F;
        p.leftArmY = 0.6F;
        p.rightArmZ = 0.2F;
        p.leftArmZ = -0.2F;
        p.rightArmWeight = weight;
        p.leftArmWeight = weight;
        p.bodyYOffset = 0.15F;
        p.headXOffset = -0.1F;
        return p;
    }

    private static PoseFrame copy(PoseFrame p) {
        PoseFrame c = new PoseFrame();
        c.rightArmX = p.rightArmX;
        c.rightArmY = p.rightArmY;
        c.rightArmZ = p.rightArmZ;
        c.leftArmX = p.leftArmX;
        c.leftArmY = p.leftArmY;
        c.leftArmZ = p.leftArmZ;
        c.rightArmWeight = p.rightArmWeight;
        c.leftArmWeight = p.leftArmWeight;
        c.bodyYOffset = p.bodyYOffset;
        c.headXOffset = p.headXOffset;
        return c;
    }

    private static void assertPoseEquals(PoseFrame expected, PoseFrame actual, float eps) {
        assertEquals("rightArmX", expected.rightArmX, actual.rightArmX, eps);
        assertEquals("rightArmY", expected.rightArmY, actual.rightArmY, eps);
        assertEquals("rightArmZ", expected.rightArmZ, actual.rightArmZ, eps);
        assertEquals("leftArmX", expected.leftArmX, actual.leftArmX, eps);
        assertEquals("leftArmY", expected.leftArmY, actual.leftArmY, eps);
        assertEquals("leftArmZ", expected.leftArmZ, actual.leftArmZ, eps);
        assertEquals("rightArmWeight", expected.rightArmWeight, actual.rightArmWeight, eps);
        assertEquals("leftArmWeight", expected.leftArmWeight, actual.leftArmWeight, eps);
        assertEquals("bodyYOffset", expected.bodyYOffset, actual.bodyYOffset, eps);
        assertEquals("headXOffset", expected.headXOffset, actual.headXOffset, eps);
    }

    @Test
    public void firstSightSeedsFromVanillaWithZeroWeightSoThereIsNoSnap() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.4F, -0.4F);
        PoseFrame out = aim(9F, 9F, 1F); // garbage that must be overwritten
        s.smooth(7, 100.0, aim(-1.4F, -1.2F, 1F), v, out);

        assertEquals(0F, out.rightArmWeight, 0F);
        assertEquals(0F, out.leftArmWeight, 0F);
        assertEquals(0.4F, out.rightArmX, EPS);
        assertEquals(-0.4F, out.leftArmX, EPS);
        assertEquals(0.1F, out.rightArmY, EPS);
        assertEquals(-0.1F, out.leftArmY, EPS);
        assertEquals(0.05F, out.rightArmZ, EPS);
        assertEquals(-0.05F, out.leftArmZ, EPS);
        assertEquals(0F, out.bodyYOffset, EPS);
        assertEquals(0F, out.headXOffset, EPS);
        assertEquals(1, s.trackedEntities());
    }

    @Test
    public void firstAdvanceFollowsExactCriticallyDampedSpringFromRest() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.2F, 0.2F);
        PoseFrame target = aim(-1.0F, -0.6F, 1F);
        PoseFrame out = new PoseFrame();
        s.smooth(1, 0.0, target, v, out);
        s.smooth(1, 0.05, target, v, out);

        double k = restDecay(0.05); // 2/e
        assertEquals(1.0 - k, out.rightArmWeight, EPS);
        assertEquals(1.0 - k, out.leftArmWeight, EPS);
        assertEquals(-1.0 + 1.2 * k, out.rightArmX, EPS);
        assertEquals(-0.6 + 0.8 * k, out.leftArmX, EPS);
        assertEquals(-0.3 + 0.4 * k, out.rightArmY, EPS);
        assertEquals(0.15 * (1.0 - k), out.bodyYOffset, EPS);
        assertEquals(-0.1 * (1.0 - k), out.headXOffset, EPS);
    }

    @Test
    public void integrationIsStepSizeInvariant() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.3F, -0.2F);
        PoseFrame target = aim(-1.3F, -0.9F, 1F);
        PoseFrame coarse = new PoseFrame();
        PoseFrame fine = new PoseFrame();

        s.smooth(1, 0.0, target, v, coarse);
        s.smooth(1, 0.08, target, v, coarse);

        s.smooth(2, 0.0, target, v, fine);
        s.smooth(2, 0.02, target, v, fine);
        s.smooth(2, 0.05, target, v, fine);
        s.smooth(2, 0.08, target, v, fine);

        assertPoseEquals(coarse, fine, EPS);
    }

    @Test
    public void fixedTargetApproachIsMonotonicAndNeverOvershoots() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.5F, 0.5F);
        PoseFrame target = aim(-1.5F, -1.5F, 1F);
        PoseFrame out = new PoseFrame();
        double t = 0.0;
        s.smooth(3, t, target, v, out);
        float prevX = out.rightArmX;
        float prevW = out.rightArmWeight;
        double[] steps = {0.004, 0.016, 0.033, 0.1, 0.007, 0.05, 0.016, 0.016, 0.09, 0.02};
        for (int round = 0; round < 6; round++) {
            for (double dt : steps) {
                t += dt;
                s.smooth(3, t, target, v, out);
                assertTrue("arm moved away from target", out.rightArmX <= prevX + 1e-6F);
                assertTrue("arm overshot target", out.rightArmX >= -1.5F - 1e-6F);
                assertTrue("weight moved away from target", out.rightArmWeight >= prevW - 1e-6F);
                assertTrue("weight overshot", out.rightArmWeight <= 1F);
                prevX = out.rightArmX;
                prevW = out.rightArmWeight;
            }
        }
    }

    @Test
    public void convergesToTargetWithinOneSecond() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.6F, -0.6F);
        PoseFrame target = aim(-1.4F, -1.1F, 1F);
        PoseFrame out = new PoseFrame();
        for (int i = 0; i <= 60; i++) {
            s.smooth(4, i * FRAME, target, v, out);
        }
        assertPoseEquals(target, out, 1e-3F);
    }

    @Test
    public void retargetMidFlightClampsAtNewTargetInsteadOfOvershooting() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0F, 0F);
        PoseFrame full = aim(-1F, -1F, 1F);
        PoseFrame out = new PoseFrame();
        s.smooth(5, 0.0, full, v, out);
        s.smooth(5, 0.05, full, v, out); // weight ~0.264, still accelerating upward with large velocity
        float reached = out.rightArmWeight;
        assertTrue(reached > 0.2F && reached < 0.3F);

        // New goal just above the current value: an unclamped spring carrying that velocity would sail past it.
        float goal = reached + 0.02F;
        PoseFrame near = aim(-1F, -1F, goal);
        double t = 0.05;
        float prev = reached;
        for (int i = 0; i < 60; i++) {
            t += FRAME;
            s.smooth(5, t, near, v, out);
            assertTrue("overshot retarget goal", out.rightArmWeight <= goal + 1e-6F);
            assertTrue("weight reversed", out.rightArmWeight >= prev - 1e-6F);
            prev = out.rightArmWeight;
        }
        assertEquals(goal, out.rightArmWeight, 1e-3F);
    }

    @Test
    public void switchingArmTargetsMovesContinuouslyThenSettles() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0F, 0F);
        PoseFrame a = aim(-1.0F, -1.0F, 1F);
        PoseFrame b = aim(0.5F, 0.2F, 1F);
        PoseFrame out = new PoseFrame();
        double t = 0.0;
        for (int i = 0; i <= 60; i++, t += FRAME) {
            s.smooth(6, t, a, v, out);
        }
        float before = out.rightArmX;
        assertEquals(-1.0F, before, 1e-3F);

        s.smooth(6, t, b, v, out);
        float jump = Math.abs(out.rightArmX - before);
        assertTrue("arm snapped on target switch: " + jump, jump < 0.1F * 1.5F);
        assertTrue("arm did not start moving toward new target", out.rightArmX > before);

        for (int i = 0; i < 60; i++) {
            t += FRAME;
            s.smooth(6, t, b, v, out);
        }
        assertEquals(0.5F, out.rightArmX, 1e-3F);
        assertEquals(0.2F, out.leftArmX, 1e-3F);
        assertEquals(1F, out.rightArmWeight, 1e-3F);
    }

    @Test
    public void providerDisappearanceDecaysWeightWhileHoldingLastArmTarget() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.3F, 0.3F);
        PoseFrame held = aim(-1.4F, -1.0F, 1F);
        PoseFrame out = new PoseFrame();
        double t = 0.0;
        for (int i = 0; i <= 60; i++, t += FRAME) {
            s.smooth(8, t, held, v, out);
        }
        assertEquals(1F, out.rightArmWeight, 1e-3F);

        PoseFrame gone = new PoseFrame();
        gone.clear();
        float prevW = out.rightArmWeight;
        for (int i = 0; i < 60; i++, t += FRAME) {
            s.smooth(8, t, gone, v, out);
            assertTrue("weight not decaying", out.rightArmWeight <= prevW + 1e-6F);
            assertTrue("weight below zero", out.rightArmWeight >= 0F);
            // Arms must fade out from the held pose, not swing toward the cleared target's zero angles.
            assertEquals(-1.4F, out.rightArmX, 2e-3F);
            assertEquals(-1.0F, out.leftArmX, 2e-3F);
            prevW = out.rightArmWeight;
        }
        assertEquals(0F, out.rightArmWeight, 1e-3F);
        assertEquals(0F, out.leftArmWeight, 1e-3F);
        assertEquals(0F, out.bodyYOffset, 1e-3F);
        assertEquals(0F, out.headXOffset, 1e-3F);
    }

    @Test
    public void entitiesAreSmoothedIndependently() {
        PoseSmoother solo = new PoseSmoother();
        PoseSmoother shared = new PoseSmoother();
        PoseFrame v = vanilla(0.2F, -0.2F);
        PoseFrame a = aim(-1.2F, -0.8F, 1F);
        PoseFrame other = aim(0.9F, 0.9F, 0.5F);
        PoseFrame otherVanilla = vanilla(-0.7F, 0.7F);
        PoseFrame soloOut = new PoseFrame();
        PoseFrame sharedOut = new PoseFrame();
        PoseFrame otherOut = new PoseFrame();

        for (int i = 0; i < 20; i++) {
            double t = i * 0.02;
            solo.smooth(1, t, a, v, soloOut);
            shared.smooth(1, t, a, v, sharedOut);
            if (i == 5) {
                shared.smooth(2, t + 0.001, other, otherVanilla, otherOut);
                // A newly seen entity is seeded from its own vanilla pose, not entity 1's state.
                assertEquals(-0.7F, otherOut.rightArmX, EPS);
                assertEquals(0F, otherOut.rightArmWeight, 0F);
            } else if (i > 5) {
                shared.smooth(2, t + 0.007, other, otherVanilla, otherOut);
            }
            assertPoseEquals(soloOut, sharedOut, 0F);
        }
        assertEquals(2, shared.trackedEntities());
        assertEquals(1, solo.trackedEntities());
    }

    @Test
    public void forgetResetsOnlyThatEntity() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.25F, -0.25F);
        PoseFrame a = aim(-1.2F, -1.2F, 1F);
        PoseFrame out1 = new PoseFrame();
        PoseFrame out2 = new PoseFrame();
        for (int i = 0; i <= 30; i++) {
            s.smooth(1, i * FRAME, a, v, out1);
            s.smooth(2, i * FRAME, a, v, out2);
        }
        assertEquals(2, s.trackedEntities());

        s.forget(1);
        assertEquals(1, s.trackedEntities());
        double t = 31 * FRAME;
        s.smooth(1, t, a, v, out1);
        assertEquals(0F, out1.rightArmWeight, 0F);
        assertEquals(0.25F, out1.rightArmX, EPS);

        PoseFrame before2 = copy(out2);
        s.smooth(2, t, a, v, out2);
        assertTrue("entity 2 state was reset by forget(1)", out2.rightArmWeight >= before2.rightArmWeight - 1e-6F);
        assertTrue(out2.rightArmWeight > 0.5F);

        s.forget(99); // unknown id is a no-op
        assertEquals(2, s.trackedEntities());
    }

    @Test
    public void clearPurgesAllEntities() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(-0.1F, 0.1F);
        PoseFrame a = aim(-1.2F, -1.2F, 1F);
        PoseFrame out = new PoseFrame();
        for (int id = 1; id <= 3; id++) {
            for (int i = 0; i <= 30; i++) {
                s.smooth(id, i * FRAME, a, v, out);
            }
        }
        assertEquals(3, s.trackedEntities());
        s.clear();
        assertEquals(0, s.trackedEntities());

        s.smooth(2, 31 * FRAME, a, v, out);
        assertEquals(0F, out.rightArmWeight, 0F);
        assertEquals(-0.1F, out.rightArmX, EPS);
        assertEquals(1, s.trackedEntities());
    }

    @Test
    public void equalTimestampDoesNotAdvance() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.2F, 0.2F);
        PoseFrame a = aim(-1.0F, -1.0F, 1F);
        PoseFrame out = new PoseFrame();
        s.smooth(1, 2.0, a, v, out);
        s.smooth(1, 2.03, a, v, out);
        PoseFrame first = copy(out);
        s.smooth(1, 2.03, a, v, out);
        s.smooth(1, 2.03, a, v, out);
        assertPoseEquals(first, out, 0F);
    }

    @Test
    public void backwardsTimeRebasesWithoutAdvancing() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.2F, 0.2F);
        PoseFrame a = aim(-1.0F, -1.0F, 1F);
        PoseFrame ref = new PoseFrame();
        PoseFrame out = new PoseFrame();

        s.smooth(1, 10.0, a, v, ref);
        s.smooth(1, 10.04, a, v, ref);
        PoseFrame afterOne = copy(ref);
        s.smooth(1, 10.08, a, v, ref);

        s.smooth(2, 10.0, a, v, out);
        s.smooth(2, 10.04, a, v, out);
        s.smooth(2, 3.0, a, v, out); // clock went backwards (e.g. world reload)
        assertPoseEquals(afterOne, out, 0F);
        s.smooth(2, 3.04, a, v, out); // advances from the rebased time, not from 10.04
        assertPoseEquals(ref, out, EPS);
    }

    @Test
    public void largeTimeGapIsClampedToOneTenthSecond() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0.4F, 0.4F);
        PoseFrame a = aim(-1.1F, -0.7F, 1F);
        PoseFrame stalled = new PoseFrame();
        PoseFrame bounded = new PoseFrame();

        s.smooth(1, 0.0, a, v, stalled);
        s.smooth(1, 5.0, a, v, stalled); // hitch / tab-out

        s.smooth(2, 0.0, a, v, bounded);
        s.smooth(2, 0.1, a, v, bounded);

        assertPoseEquals(bounded, stalled, EPS);
        double k = restDecay(0.1);
        assertEquals(1.0 - k, stalled.rightArmWeight, EPS);
        assertTrue("a hitch must not complete the blend", stalled.rightArmWeight < 0.95F);
    }

    @Test
    public void outputWeightsStayWithinUnitInterval() {
        PoseSmoother s = new PoseSmoother();
        PoseFrame v = vanilla(0F, 0F);
        PoseFrame over = aim(-1F, -1F, 1F);
        over.rightArmWeight = 2.5F;
        over.leftArmWeight = -1.5F;
        PoseFrame out = new PoseFrame();
        for (int i = 0; i <= 120; i++) {
            s.smooth(1, i * FRAME, over, v, out);
            assertTrue(out.rightArmWeight >= 0F && out.rightArmWeight <= 1F);
            assertTrue(out.leftArmWeight >= 0F && out.leftArmWeight <= 1F);
        }
        assertEquals(1F, out.rightArmWeight, 1e-3F);
        assertEquals(0F, out.leftArmWeight, 1e-3F);
    }
}
