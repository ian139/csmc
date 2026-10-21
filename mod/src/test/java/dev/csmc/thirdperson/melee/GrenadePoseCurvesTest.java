package dev.csmc.thirdperson.melee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.csmc.thirdperson.melee.GrenadePoseCurves.Pose;
import dev.csmc.thirdperson.melee.GrenadePoseCurves.Stage;
import org.junit.Test;

public class GrenadePoseCurvesTest {
    private static final float EPS = 1.0E-4F;
    private static final double THROW = GrenadePoseCurves.THROW_SECONDS;

    private static Pose pose(Stage stage, double age, int variant, boolean holding) {
        Pose out = new Pose();
        GrenadePoseCurves.pose(stage, age, stage == Stage.THROW ? THROW : 0.0, variant, 0F, holding, out);
        return out;
    }

    private static Pose ready() {
        return pose(Stage.READY, 0.0, 0, true);
    }

    @Test
    public void grenadeInHandIsClaimedAndOtherItemsAreNot() {
        assertTrue(GrenadePoseCurves.claims(true, false, Stage.READY, 0.0, 0.0));
        assertTrue(GrenadePoseCurves.claims(true, false, Stage.PRIME, 3.0, 0.0));
        assertFalse(GrenadePoseCurves.claims(false, false, Stage.READY, 0.0, 0.0));
        // A prime left behind by a slot change never keeps a grenade pose on an empty hand or another item.
        assertFalse(GrenadePoseCurves.claims(false, true, Stage.PRIME, 0.1, 0.0));
        assertFalse(GrenadePoseCurves.claims(false, false, Stage.PRIME, 0.1, 0.0));
    }

    @Test
    public void lastGrenadeThrownKeepsRecoveryOnlyOnEmptyHandUntilExpiry() {
        assertTrue(GrenadePoseCurves.claims(false, true, Stage.THROW, 0.0, THROW));
        assertTrue(GrenadePoseCurves.claims(false, true, Stage.THROW, 0.49, THROW));
        assertFalse(GrenadePoseCurves.claims(false, true, Stage.THROW, 0.5, THROW));
        // Newly holding a gun or knife: never a grenade throw pose.
        assertFalse(GrenadePoseCurves.claims(false, false, Stage.THROW, 0.1, THROW));
    }

    @Test
    public void idleGrenadeIsHeldForwardInTheReadyGrip() {
        Pose ready = ready();
        assertTrue(ready.rightArmX < -0.5F && ready.rightArmX > -1.2F);
        assertTrue("support hand rests toward the grenade", ready.leftArmY > 0.2F);
        assertEquals(1F, ready.weight, EPS);
        Pose aimingUp = new Pose();
        GrenadePoseCurves.pose(Stage.READY, 0.0, 0.0, 0, -0.6F, true, aimingUp);
        assertTrue("grip follows aim pitch", aimingUp.rightArmX < ready.rightArmX - 0.2F);
    }

    @Test
    public void primeStartsFromReadyThenPullsPinThenCocksArmAndPersists() {
        Pose start = pose(Stage.PRIME, 0.0, 0, true);
        Pose ready = ready();
        assertEquals(ready.rightArmX, start.rightArmX, EPS);
        assertEquals(ready.leftArmY, start.leftArmY, EPS);

        Pose reach = pose(Stage.PRIME, GrenadePoseCurves.PIN_REACH_SECONDS, 0, true);
        assertTrue("support hand crosses to the grenade", reach.leftArmY > 0.6F);
        assertTrue("support arm raised to chest", reach.leftArmX < -0.9F);
        assertTrue("throwing arm not yet cocked", reach.rightArmX > -1.5F);

        Pose pulled = pose(Stage.PRIME, GrenadePoseCurves.PIN_PULLED_SECONDS, 0, true);
        assertTrue("support hand yanks the pin away", pulled.leftArmY < reach.leftArmY - 0.5F);
        assertTrue("throwing arm still at chest while pin is pulled", pulled.rightArmX > -1.5F);

        Pose cocked = pose(Stage.PRIME, GrenadePoseCurves.COCKED_SECONDS, 0, true);
        assertTrue("throwing arm cocked overhead", cocked.rightArmX < -2.5F);

        Pose relaxed = pose(Stage.PRIME, GrenadePoseCurves.SUPPORT_RELAXED_SECONDS, 0, true);
        assertTrue("support hand relaxes after the pin", relaxed.leftArmX > cocked.leftArmX + 0.3F);
        Pose held = pose(Stage.PRIME, 5.0, 0, true);
        assertEquals(relaxed.rightArmX, held.rightArmX, EPS);
        assertEquals(relaxed.leftArmX, held.leftArmX, EPS);
    }

    @Test
    public void primeAndThrowCurvesAreSmooth() {
        assertSmooth(Stage.PRIME, 0, 1.0);
        for (int variant = 0; variant < 3; variant++) assertSmooth(Stage.THROW, variant, THROW - 1.0E-6);
    }

    /** No discontinuities: every channel moves under 72 rad/s (a fast throwing arm), sampled at 240 Hz. */
    private static void assertSmooth(Stage stage, int variant, double end) {
        double step = 1.0 / 240.0;
        float maxStep = 0.3F;
        Pose previous = pose(stage, 0.0, variant, true);
        for (double age = step; age <= end; age += step) {
            Pose next = pose(stage, age, variant, true);
            assertTrue(stage + " " + variant + " jump at " + age,
                    Math.abs(next.rightArmX - previous.rightArmX) < maxStep
                            && Math.abs(next.rightArmY - previous.rightArmY) < maxStep
                            && Math.abs(next.rightArmZ - previous.rightArmZ) < maxStep
                            && Math.abs(next.leftArmX - previous.leftArmX) < maxStep
                            && Math.abs(next.leftArmY - previous.leftArmY) < maxStep
                            && Math.abs(next.leftArmZ - previous.leftArmZ) < maxStep);
            previous = next;
        }
    }

    @Test
    public void throwContinuesFromTheHeldPrime() {
        Pose held = pose(Stage.PRIME, 5.0, 0, true);
        for (int variant = 0; variant < 3; variant++) {
            Pose release = pose(Stage.THROW, 0.0, variant, true);
            assertEquals(held.rightArmX, release.rightArmX, EPS);
            assertEquals(held.leftArmX, release.leftArmX, EPS);
        }
    }

    @Test
    public void overhandAndLobReleasesAreDistinct() {
        Pose overhandWhip = pose(Stage.THROW, 0.08, GrenadePoseCurves.OVERHAND, true);
        Pose lobDrop = pose(Stage.THROW, 0.1, GrenadePoseCurves.LOB, true);
        assertTrue("overhand releases above the shoulder", overhandWhip.rightArmX < -1.6F);
        assertTrue("lob swings the arm down and back", lobDrop.rightArmX > 0F);

        Pose overhandFollow = pose(Stage.THROW, 0.16, GrenadePoseCurves.OVERHAND, true);
        Pose lobRelease = pose(Stage.THROW, 0.2, GrenadePoseCurves.LOB, true);
        assertTrue("overhand follows through downward", overhandFollow.rightArmX > -0.6F);
        assertTrue("lob releases underhand, forward and low",
                lobRelease.rightArmX < -1.0F && lobRelease.rightArmX > -1.8F);

        Pose medium = pose(Stage.THROW, 0.06, GrenadePoseCurves.MEDIUM, true);
        assertTrue("medium is a side-arm toss", medium.rightArmZ > 0.6F && medium.rightArmY > 0.5F);
        assertTrue(medium.rightArmX > overhandWhip.rightArmX && medium.rightArmX < lobRelease.rightArmX + 0.5F);

        Pose unknown = pose(Stage.THROW, 0.08, 7, true);
        assertEquals(overhandWhip.rightArmX, unknown.rightArmX, EPS);
    }

    @Test
    public void throwRecoversToReadyAndExpires() {
        Pose ready = ready();
        for (int variant = 0; variant < 3; variant++) {
            Pose nearEnd = pose(Stage.THROW, 0.49, variant, true);
            assertEquals(ready.rightArmX, nearEnd.rightArmX, 0.05F);
            assertEquals(ready.leftArmY, nearEnd.leftArmY, 0.05F);
            Pose expired = pose(Stage.THROW, THROW, variant, true);
            assertEquals(ready.rightArmX, expired.rightArmX, EPS);
            assertEquals(ready.leftArmX, expired.leftArmX, EPS);
            assertEquals(1F, expired.weight, EPS);
        }
        // A throw received with another duration is rescaled to it.
        Pose scaled = new Pose();
        GrenadePoseCurves.pose(Stage.THROW, 0.16, 1.0, GrenadePoseCurves.OVERHAND, 0F, true, scaled);
        assertEquals(pose(Stage.THROW, 0.08, GrenadePoseCurves.OVERHAND, true).rightArmX, scaled.rightArmX, EPS);
    }

    @Test
    public void lastGrenadeRecoveryFadesToVanillaArms() {
        assertEquals(1F, pose(Stage.THROW, 0.3, GrenadePoseCurves.OVERHAND, false).weight, EPS);
        float fading = pose(Stage.THROW, 0.42, GrenadePoseCurves.OVERHAND, false).weight;
        assertTrue(fading > 0.1F && fading < 0.9F);
        assertTrue(pose(Stage.THROW, 0.49, GrenadePoseCurves.OVERHAND, false).weight < 0.1F);
    }

    @Test
    public void cancelReturnsToReady() {
        Pose out = new Pose();
        GrenadePoseCurves.pose(Stage.PRIME, 2.0, 0.0, 0, 0F, true, out);
        assertTrue(out.rightArmX < -2.5F);
        GrenadePoseCurves.pose(Stage.READY, 0.0, 0.0, 0, 0F, true, out);
        Pose ready = ready();
        assertEquals(ready.rightArmX, out.rightArmX, EPS);
        assertEquals(ready.rightArmY, out.rightArmY, EPS);
        assertEquals(ready.leftArmX, out.leftArmX, EPS);
        assertEquals(ready.leftArmY, out.leftArmY, EPS);
        assertEquals(ready.bodyYOffset, out.bodyYOffset, EPS);
        assertEquals(1F, out.weight, EPS);
    }
}
