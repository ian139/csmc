package dev.csmc.thirdperson.blend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Numeric behaviour of the pure movement offset layer: grounded walk bob, airborne arm raise scaled by vertical speed,
 * crouch lean compensation and the short landing settle. Only arm X/Z may change.
 */
public class MovementLayerTest {
    private static final float EPS = 1e-5F;
    private static final double LONG_AGO = 10.0;
    /** limbSwing at which cos(limbSwing * 0.6662) == -1. */
    private static final float HALF_CYCLE = (float) (Math.PI / 0.6662);

    private static PoseFrame base() {
        PoseFrame p = new PoseFrame();
        p.rightArmX = -1.2F;
        p.rightArmY = -0.1F;
        p.rightArmZ = 0.05F;
        p.leftArmX = -1.0F;
        p.leftArmY = 0.4F;
        p.leftArmZ = -0.05F;
        p.rightArmWeight = 0.8F;
        p.leftArmWeight = 0.6F;
        p.bodyYOffset = 0.12F;
        p.headXOffset = -0.07F;
        return p;
    }

    private static PoseFrame apply(boolean airborne, boolean crouching, double vSpeed, float limbSwing, float amount,
            double sinceLanding) {
        PoseFrame p = base();
        MovementLayer.applyOffsets(p, airborne, crouching, vSpeed, limbSwing, amount, sinceLanding);
        return p;
    }

    private static float dRightX(PoseFrame p) {
        return p.rightArmX - base().rightArmX;
    }

    private static float dLeftX(PoseFrame p) {
        return p.leftArmX - base().leftArmX;
    }

    private static void assertNonArmXZUntouched(PoseFrame p) {
        PoseFrame b = base();
        assertEquals(b.rightArmY, p.rightArmY, 0F);
        assertEquals(b.leftArmY, p.leftArmY, 0F);
        assertEquals(b.rightArmWeight, p.rightArmWeight, 0F);
        assertEquals(b.leftArmWeight, p.leftArmWeight, 0F);
        assertEquals(b.bodyYOffset, p.bodyYOffset, 0F);
        assertEquals(b.headXOffset, p.headXOffset, 0F);
    }

    private static void assertSamePose(PoseFrame a, PoseFrame b) {
        assertEquals(a.rightArmX, b.rightArmX, EPS);
        assertEquals(a.rightArmZ, b.rightArmZ, EPS);
        assertEquals(a.leftArmX, b.leftArmX, EPS);
        assertEquals(a.leftArmZ, b.leftArmZ, EPS);
    }

    private static double landing(double t) {
        return 0.10 * Math.sin(Math.PI * t / 0.18) * (1.0 - t / 0.18);
    }

    @Test
    public void standingStillLeavesPoseUnchanged() {
        PoseFrame p = apply(false, false, 0.0, 3.7F, 0F, LONG_AGO);
        assertSamePose(base(), p);
        assertNonArmXZUntouched(p);
    }

    @Test
    public void groundedWalkBobSwingsArmsInOpposition() {
        PoseFrame peak = apply(false, false, 0.0, 0F, 1F, LONG_AGO);
        assertEquals(0.07F, dRightX(peak), EPS);
        assertEquals(-0.07F, dLeftX(peak), EPS);

        PoseFrame trough = apply(false, false, 0.0, HALF_CYCLE, 1F, LONG_AGO);
        assertEquals(-0.07F, dRightX(trough), EPS);
        assertEquals(0.07F, dLeftX(trough), EPS);

        float swing = 1.3F;
        float amount = 0.5F;
        PoseFrame mid = apply(false, false, 0.0, swing, amount, LONG_AGO);
        double expected = Math.cos(swing * 0.6662) * amount * 0.07;
        assertEquals(expected, dRightX(mid), EPS);
        assertEquals(-expected, dLeftX(mid), EPS);
        assertNonArmXZUntouched(mid);
    }

    @Test
    public void walkBobRollIsSmall() {
        for (float swing = 0F; swing < 2 * HALF_CYCLE; swing += 0.37F) {
            PoseFrame p = apply(false, false, 0.0, swing, 1F, LONG_AGO);
            assertTrue(Math.abs(p.rightArmZ - base().rightArmZ) <= 0.025F + EPS);
            assertTrue(Math.abs(p.leftArmZ - base().leftArmZ) <= 0.025F + EPS);
        }
    }

    @Test
    public void limbSwingAmountIsClampedToUnitRange() {
        assertSamePose(apply(false, false, 0.0, 0.9F, 1F, LONG_AGO), apply(false, false, 0.0, 0.9F, 3.5F, LONG_AGO));
        assertSamePose(apply(false, false, 0.0, 0.9F, 0F, LONG_AGO), apply(false, false, 0.0, 0.9F, -0.8F, LONG_AGO));
        assertSamePose(base(), apply(false, false, 0.0, 0.9F, -0.8F, LONG_AGO));
    }

    @Test
    public void airborneSuppressesWalkBobAndRaisesBothArms() {
        PoseFrame a = apply(true, false, 0.0, 0F, 1F, LONG_AGO);
        PoseFrame b = apply(true, false, 0.0, HALF_CYCLE, 1F, LONG_AGO);
        PoseFrame c = apply(true, false, 0.0, 1.9F, 0F, LONG_AGO);
        assertSamePose(a, b);
        assertSamePose(a, c);

        assertEquals(-0.08F, dRightX(a), EPS);
        assertEquals(-0.08F, dLeftX(a), EPS);
        assertNonArmXZUntouched(a);
    }

    @Test
    public void airborneRaiseScalesWithVerticalSpeedAndCaps() {
        assertEquals(-(0.08 + 0.25 * 0.12), dRightX(apply(true, false, 0.25, 0F, 0F, LONG_AGO)), EPS);
        assertEquals(-(0.08 + 0.25 * 0.12), dLeftX(apply(true, false, 0.25, 0F, 0F, LONG_AGO)), EPS);
        // Falling raises the arms as much as rising.
        assertEquals(-(0.08 + 0.25 * 0.12), dRightX(apply(true, false, -0.25, 0F, 0F, LONG_AGO)), EPS);
        assertEquals(-(0.08 + 0.5 * 0.12), dRightX(apply(true, false, -0.5, 0F, 0F, LONG_AGO)), EPS);
        // Strength saturates at 0.10 extra.
        assertEquals(-0.18F, dRightX(apply(true, false, 2.0, 0F, 0F, LONG_AGO)), EPS);
        assertEquals(-0.18F, dRightX(apply(true, false, -40.0, 0F, 0F, LONG_AGO)), EPS);

        float slow = dRightX(apply(true, false, 0.1, 0F, 0F, LONG_AGO));
        float fast = dRightX(apply(true, false, 0.6, 0F, 0F, LONG_AGO));
        assertTrue("faster vertical motion must raise arms more", fast < slow);
    }

    @Test
    public void airborneNeverAppliesLandingSettle() {
        assertSamePose(apply(true, false, 0.3, 0F, 0F, LONG_AGO), apply(true, false, 0.3, 0F, 0F, 0.09));
        assertSamePose(apply(true, false, 0.3, 0F, 0F, LONG_AGO), apply(true, false, 0.3, 0F, 0F, 0.0));
    }

    @Test
    public void crouchAddsLeanCompensationToBothArms() {
        PoseFrame crouch = apply(false, true, 0.0, 0F, 0F, LONG_AGO);
        assertEquals(0.28F, dRightX(crouch), EPS);
        assertEquals(0.28F, dLeftX(crouch), EPS);
        assertNonArmXZUntouched(crouch);

        float swing = 2.2F;
        PoseFrame walk = apply(false, false, 0.0, swing, 0.7F, LONG_AGO);
        PoseFrame crouchWalk = apply(false, true, 0.0, swing, 0.7F, LONG_AGO);
        assertEquals(0.28F, crouchWalk.rightArmX - walk.rightArmX, EPS);
        assertEquals(0.28F, crouchWalk.leftArmX - walk.leftArmX, EPS);
    }

    @Test
    public void landingSettleFollowsDampedPulse() {
        double[] times = {0.02, 0.045, 0.09, 0.135, 0.17};
        for (double t : times) {
            PoseFrame p = apply(false, false, 0.0, 0F, 0F, t);
            assertEquals("t=" + t, landing(t), dRightX(p), EPS);
            assertEquals("t=" + t, landing(t), dLeftX(p), EPS);
            assertTrue("settle must push arms down (positive X)", dRightX(p) > 0F);
            assertNonArmXZUntouched(p);
        }
        assertEquals(0.05F, dRightX(apply(false, false, 0.0, 0F, 0F, 0.09)), EPS);
    }

    @Test
    public void landingSettleVanishesOutsideItsWindow() {
        assertEquals(0F, dRightX(apply(false, false, 0.0, 0F, 0F, 0.0)), EPS);
        assertEquals(0F, dRightX(apply(false, false, 0.0, 0F, 0F, 0.18)), EPS);
        assertEquals(0F, dRightX(apply(false, false, 0.0, 0F, 0F, 0.181)), 0F);
        assertEquals(0F, dRightX(apply(false, false, 0.0, 0F, 0F, 0.5)), 0F);
        assertEquals(0F, dLeftX(apply(false, false, 0.0, 0F, 0F, 0.3)), 0F);
        assertSamePose(base(), apply(false, false, 0.0, 0F, 0F, 0.25));
    }

    @Test
    public void landingSettleStacksOnWalkBob() {
        float swing = 0.4F;
        PoseFrame walk = apply(false, false, 0.0, swing, 1F, LONG_AGO);
        PoseFrame landingWalk = apply(false, false, 0.0, swing, 1F, 0.06);
        assertEquals(landing(0.06), landingWalk.rightArmX - walk.rightArmX, EPS);
        assertEquals(landing(0.06), landingWalk.leftArmX - walk.leftArmX, EPS);
    }
}
