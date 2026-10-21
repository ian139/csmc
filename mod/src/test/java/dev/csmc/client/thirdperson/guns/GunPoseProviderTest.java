package dev.csmc.client.thirdperson.guns;

import dev.csmc.thirdperson.guns.GunPoseMath;
import dev.csmc.thirdperson.guns.GunPoseMath.Action;
import dev.csmc.thirdperson.guns.GunPoseMath.Grip;
import dev.csmc.thirdperson.guns.GunPoseMath.Pose;
import org.junit.Test;
import static org.junit.Assert.*;

public class GunPoseProviderTest {
    private Pose pose(Grip grip, float pitch, boolean scope, Action action, double t) {
        Pose out = new Pose();
        GunPoseMath.sample(grip, pitch, scope, action == null ? Action.NONE : action, t, out);
        return out;
    }

    @Test public void choosesPistolAndLongGunFamilies() {
        assertEquals(Grip.PISTOL, GunPoseMath.grip("revolver"));
        assertEquals(Grip.SMG, GunPoseMath.grip("mp9"));
        assertEquals(Grip.SNIPER, GunPoseMath.grip("awp"));
        assertEquals(Grip.SHOTGUN, GunPoseMath.grip("nova"));
        assertEquals(Grip.RIFLE, GunPoseMath.grip("ak47"));
    }

    @Test public void bothArmsFollowAimAndSupportHandCrossesInward() {
        for (var grip : Grip.values()) {
            Pose level = pose(grip, 0, false, null, 0);
            Pose up = pose(grip, -.65f, false, null, 0);
            assertEquals(-.65f, up.rightX - level.rightX, .00001f);
            assertEquals(-.65f, up.leftX - level.leftX, .00001f);
            assertTrue(level.leftY > .4f);
        }
    }

    @Test public void scopeShouldersRifleTighterButDoesNotChangePistol() {
        Pose base = pose(Grip.SNIPER, 0, false, null, 0);
        Pose scope = pose(Grip.SNIPER, 0, true, null, 0);
        assertTrue(scope.rightX < base.rightX);
        assertTrue(scope.rightY < base.rightY);
        assertEquals(pose(Grip.PISTOL, 0, false, null, 0).rightX,
                pose(Grip.PISTOL, 0, true, null, 0).rightX, 0);
    }

    @Test public void recoilKicksImmediatelyThenRecoversExactlyAtCycleEnd() {
        float idle = pose(Grip.RIFLE, 0, false, null, 0).rightX;
        float peak = pose(Grip.RIFLE, 0, false, Action.FIRE, .12).rightX;
        float recovered = pose(Grip.RIFLE, 0, false, Action.FIRE, .8).rightX;
        assertTrue(peak < idle);
        assertTrue(recovered > peak && recovered < idle);
        assertEquals(idle, pose(Grip.RIFLE, 0, false, Action.FIRE, 1).rightX, 0);
        assertTrue(GunPoseMath.recoil(.03f) > 0);
        float sniperKick = idle - pose(Grip.SNIPER, 0, false, Action.FIRE, .12).rightX;
        assertTrue(sniperKick > idle - peak);
    }

    @Test public void reloadDropsMagazineHandThenPullsBoltAndReturns() {
        Pose idle = pose(Grip.RIFLE, 0, false, null, 0);
        Pose mag = pose(Grip.RIFLE, 0, false, Action.RELOAD, .4);
        Pose bolt = pose(Grip.RIFLE, 0, false, Action.RELOAD, .82);
        Pose end = pose(Grip.RIFLE, 0, false, Action.RELOAD, 1);
        assertTrue(mag.leftX > idle.leftX + .5);
        assertTrue(bolt.leftY > mag.leftY);
        assertTrue(bolt.leftX < mag.leftX);
        assertEquals(idle.leftX, end.leftX, 0);
        assertEquals(idle.rightX, end.rightX, 0);
    }

    @Test public void drawRaisesAndInspectTiltsThenReturns() {
        float idle = pose(Grip.PISTOL, 0, false, null, 0).rightX;
        assertTrue(pose(Grip.PISTOL, 0, false, Action.DRAW, 0).rightX > idle);
        assertEquals(idle, pose(Grip.PISTOL, 0, false, Action.DRAW, 1).rightX, 0);
        assertTrue(pose(Grip.PISTOL, 0, false, Action.INSPECT, .5).rightZ < -.2f);
        assertEquals(.035f, pose(Grip.PISTOL, 0, false, Action.INSPECT, 1).rightZ, 0);
    }

    @Test public void serverDurationAndCancellationDriveActionSelection() {
        assertEquals(Action.RELOAD, GunPoseMath.action("reload"));
        assertEquals(3.7, GunPoseMath.duration(Action.RELOAD, 3.7, .1, 1), 0);
        assertEquals(Action.NONE, GunPoseMath.action(null));
        assertEquals(0, GunPoseMath.duration(GunPoseMath.action(null), 3.7, .1, 1), 0);
        assertEquals(.1, GunPoseMath.duration(GunPoseMath.action("fire"), 0, .1, 1), 0);
        assertEquals(.8, GunPoseMath.duration(GunPoseMath.action("draw"), 0, .1, .8), 0);
    }

    @Test public void itemCorrectionFollowsAppliedElevationAndClaimWeight() {
        assertEquals(0, GunPoseMath.heldItemCorrection(-1.46f, 0, 0), 0);
        assertEquals(-1.46f, GunPoseMath.heldItemCorrection(-1.46f, 0, 1), .00001f);
        assertEquals(-.25f, GunPoseMath.heldItemCorrection(-.5f, 0, .5f), .00001f);
        assertEquals(-1.46f, GunPoseMath.heldItemCorrection(-2.06f, -.6f, 1), .00001f);
        assertEquals(-(float) (Math.PI / 2), GunPoseMath.heldItemCorrection(-2, 0, 1), .00001f);
    }

    @Test public void cancelRemovesReloadOffsetsOnTheNextSample() {
        Pose reused = new Pose();
        GunPoseMath.sample(Grip.RIFLE, 0, false, Action.RELOAD, .4, reused);
        assertTrue(reused.leftX > -1);
        GunPoseMath.sample(Grip.RIFLE, 0, false, GunPoseMath.action(null), .4, reused);
        Pose idle = pose(Grip.RIFLE, 0, false, Action.NONE, .4);
        assertEquals(idle.leftX, reused.leftX, 0);
        assertEquals(idle.leftY, reused.leftY, 0);
        assertEquals(idle.rightX, reused.rightX, 0);
        assertEquals(idle.rightZ, reused.rightZ, 0);
    }
}
