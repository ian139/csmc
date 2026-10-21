package dev.csmc.thirdperson.blend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PoseAnglesTest {
    private static final float EPS = 1e-5F;

    @Test
    public void movingVanillaAcrossOppositeDirectionDoesNotFlipFractionalPose() {
        float before = PoseAngles.blend(0.340F, -2.8F, 0.5F);
        float after = PoseAngles.blend(0.343F, -2.8F, 0.5F);

        assertEquals(-1.230F, before, EPS);
        assertEquals(-1.2285F, after, EPS);
        assertEquals(0.0015F, after - before, EPS);
    }

    @Test
    public void fadingHeldGrenadeStaysContinuousAcrossTheOppositeDirection() {
        float previous = PoseAngles.blend(0.340F, -2.8F, 0.5F);
        for (int frame = 1; frame <= 100; frame++) {
            float vanilla = 0.340F + frame * 0.003F;
            float weight = 0.5F * (1F - frame / 100F);
            float composed = PoseAngles.blend(vanilla, -2.8F, weight);
            assertTrue("fractional composition snapped", Math.abs(composed - previous) < 0.025F);
            previous = composed;
        }
        assertEquals(0.640F, previous, EPS);
    }

    @Test
    public void smootherKeepsItsInitialBranchWhileVanillaCrossesThePoseAntipode() {
        PoseSmoother smoother = new PoseSmoother();
        PoseFrame vanilla = new PoseFrame();
        PoseFrame target = new PoseFrame();
        PoseFrame smoothed = new PoseFrame();
        vanilla.rightArmX = 0.340F;
        target.rightArmX = -2.8F;
        target.rightArmWeight = 0.5F;
        for (int frame = 0; frame <= 120; frame++) {
            smoother.smooth(1, frame / 60.0, target, vanilla, smoothed);
        }
        float before = PoseAngles.blend(vanilla.rightArmX, smoothed.rightArmX, smoothed.rightArmWeight);
        vanilla.rightArmX = 0.343F;
        smoother.smooth(1, 121 / 60.0, target, vanilla, smoothed);
        float after = PoseAngles.blend(vanilla.rightArmX, smoothed.rightArmX, smoothed.rightArmWeight);

        assertEquals(-2.8F, smoothed.rightArmX, EPS);
        assertEquals(0.0015F, after - before, EPS);
    }

    @Test
    public void weightsSelectEndpointsWithoutChangingThePoseBranch() {
        float unwrapped = (float) (Math.PI * 2.0 - 2.8);
        assertEquals(0.343F, PoseAngles.blend(0.343F, unwrapped, 0F), EPS);
        assertEquals(unwrapped, PoseAngles.blend(0.343F, unwrapped, 1F), EPS);
        assertEquals((0.343F + unwrapped) * 0.5F, PoseAngles.blend(0.343F, unwrapped, 0.5F), EPS);
    }
}
