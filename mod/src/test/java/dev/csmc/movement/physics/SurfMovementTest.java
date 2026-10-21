package dev.csmc.movement.physics;

import static dev.csmc.movement.physics.MovementFixtures.HALF_WIDTH;
import static dev.csmc.movement.physics.MovementFixtures.HEIGHT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.List;
import org.junit.Test;

/**
 * Surfing: a plane with 0 < normal y < 0.7 is not ground (gamemovement.cpp CategorizePosition), so the player stays
 * airborne and TryPlayerMove/ClipVelocity slide them along it without friction. Ramps here are brushes built like
 * Minecraft surf blocks: one solid, or the same solid split into block cells, also far from the origin and yawed.
 */
public class SurfMovementTest {
    private static final CsMovementParams SURF = CsMovementParams.SURF;
    private static final double SLOPE = 60; // normal y = cos 60 = 0.5
    private static final double RAMP_HEIGHT = 16;
    private static final double ALONG = 600;
    /** Up-slope launch speed: decelerating at g sin 60 it returns to its start height after ~2 s. */
    private static final double UP = 693;
    /** Speed into the face on arrival, so the very first step makes contact. */
    private static final double INTO = 60;

    private static List<BrushPlane> ramp(MovementFixtures.Frame frame) {
        return MovementFixtures.rampPlanes(frame, SLOPE, RAMP_HEIGHT, -5, 45);
    }

    /**
     * Surfs for 2 s from local slope height 4 blocks, strafing into the ramp while facing along it, and checks every
     * step: surfing, never grounded, no startSolid, and the along-ramp velocity component unchanged (no friction, no
     * seam or edge hit). Returns the per-step positions.
     */
    private static double[][] surf(MovementFixtures.Frame frame, ConvexBrushWorld world) {
        double dt = MovementFixtures.dt(SURF);
        int steps = (int) Math.round(2.0 / dt);
        double a = Math.toRadians(SLOPE);
        double[] axis = frame.dir(0, 0, 1);
        double[] up = frame.dir(Math.cos(a), Math.sin(a), 0);
        double[] origin = MovementFixtures.originOnSlope(frame, SLOPE, 4, 0, 0.5);
        CsPlayerState s = new CsPlayerState();
        s.origin.set(origin[0], origin[1], origin[2]);
        double[] n = frame.dir(-Math.sin(a), Math.cos(a), 0);
        // Arriving on the face: along the ramp, up the slope, and into it.
        s.velocity.set(axis[0] * ALONG + up[0] * UP - n[0] * INTO, up[1] * UP - n[1] * INTO,
                axis[2] * ALONG + up[2] * UP - n[2] * INTO);
        double yaw = MovementFixtures.yawFacing(axis[0], axis[2]);
        // Facing along the ramp, the solid is on the left (local +x): strafe left into it.
        CsMoveInput into = MovementFixtures.input(0, -450, false, yaw);
        CsTrace probe = new CsTrace();
        double[][] positions = new double[steps][];
        for (int i = 0; i < steps; i++) {
            CsGameMovement.step(s, into, SURF, world, dt);
            assertTrue("step " + i + " lost the ramp", s.surfing);
            assertFalse("step " + i + " grounded on a 60 degree ramp", s.grounded);
            world.traceHull(s.origin.x, s.origin.y, s.origin.z, s.origin.x, s.origin.y, s.origin.z, HALF_WIDTH,
                    HEIGHT, probe);
            assertFalse("step " + i + " embedded", probe.startSolid);
            double along = s.velocity.x * axis[0] + s.velocity.z * axis[2];
            assertEquals("step " + i + " along-ramp speed", ALONG, along, 1e-6);
            positions[i] = new double[] {s.origin.x, s.origin.y, s.origin.z};
        }
        // Still on the ramp face above its foot after 2 s.
        double localHeight = s.origin.y / CsUnits.UNITS_PER_BLOCK - frame.ty;
        assertTrue("height " + localHeight, localHeight > 0.5 && localHeight < RAMP_HEIGHT);
        return positions;
    }

    @Test
    public void sixtyDegreeRampKeepsTheSurferForTwoSeconds() {
        MovementFixtures.Frame frame = new MovementFixtures.Frame(0, 0, 0, 0);
        surf(frame, new ConvexBrushWorld(List.of(MovementFixtures.brush(ramp(frame), 0.01))));
    }

    @Test
    public void rampBuiltFromBlockCellsHasNoSeams() {
        MovementFixtures.Frame frame = new MovementFixtures.Frame(0, 0, 0, 0);
        double[][] whole = surf(frame, new ConvexBrushWorld(List.of(MovementFixtures.brush(ramp(frame), 0.01))));
        List<ConvexBrush> cells = MovementFixtures.splitIntoCells(ramp(frame));
        assertTrue(cells.size() > 1000);
        double[][] split = surf(frame, new ConvexBrushWorld(cells));
        assertSameTrajectory(whole, split, 0, 0, 0, 1e-6);
    }

    @Test
    public void blockCellRampFarFromTheOriginHasNoSeams() {
        MovementFixtures.Frame base = new MovementFixtures.Frame(0, 0, 0, 0);
        double[][] whole = surf(base, new ConvexBrushWorld(List.of(MovementFixtures.brush(ramp(base), 0.01))));
        MovementFixtures.Frame far = new MovementFixtures.Frame(0, 100000, 0, 100000);
        double[][] split = surf(far, new ConvexBrushWorld(MovementFixtures.splitIntoCells(ramp(far))));
        double u = CsUnits.UNITS_PER_BLOCK;
        assertSameTrajectory(whole, split, 100000 * u, 0, 100000 * u, 1e-4);
    }

    @Test
    public void yawRotatedBlockCellRampHasNoGhostHits() {
        MovementFixtures.Frame frame = new MovementFixtures.Frame(30, 0.37, 0, -0.81);
        double[][] whole = surf(frame, new ConvexBrushWorld(List.of(MovementFixtures.brush(ramp(frame), 0.01))));
        double[][] split = surf(frame, new ConvexBrushWorld(MovementFixtures.splitIntoCells(ramp(frame))));
        assertSameTrajectory(whole, split, 0, 0, 0, 1e-6);
    }

    private static void assertSameTrajectory(double[][] a, double[][] b, double ox, double oy, double oz,
            double tolerance) {
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals("x step " + i, a[i][0], b[i][0] - ox, tolerance);
            assertEquals("y step " + i, a[i][1], b[i][1] - oy, tolerance);
            assertEquals("z step " + i, a[i][2], b[i][2] - oz, tolerance);
        }
    }

    @Test
    public void leavingTheRampTopGoesAirborne() {
        MovementFixtures.Frame frame = new MovementFixtures.Frame(0, 0, 0, 0);
        List<BrushPlane> planes = MovementFixtures.rampPlanes(frame, SLOPE, 4, -5, 45);
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.brush(planes, 0.01)));
        double dt = MovementFixtures.dt(SURF);
        double a = Math.toRadians(SLOPE);
        double[] origin = MovementFixtures.originOnSlope(frame, SLOPE, 1, 0, 0.5);
        CsPlayerState s = new CsPlayerState();
        s.origin.set(origin[0], origin[1], origin[2]);
        s.velocity.set(Math.cos(a) * 1200 + Math.sin(a) * INTO, Math.sin(a) * 1200 - Math.cos(a) * INTO, ALONG);
        CsMoveInput into = MovementFixtures.input(0, -450, false, 0);
        double ridgeX = 4 / Math.tan(a) * CsUnits.UNITS_PER_BLOCK;
        int surfed = 0;
        int airborneAfterRidge = 0;
        for (int i = 0; i < 80; i++) {
            CsGameMovement.step(s, into, SURF, world, dt);
            assertFalse(s.grounded);
            if (s.surfing) surfed++;
            if (s.origin.x - HALF_WIDTH > ridgeX) {
                assertFalse("touched nothing past the ridge", s.surfing);
                airborneAfterRidge++;
            }
        }
        assertTrue(surfed > 5);
        assertTrue(airborneAfterRidge > 20);
        assertTrue("flies off upward", s.origin.y > 4 * CsUnits.UNITS_PER_BLOCK);
    }

    @Test
    public void landingOnAThirtyDegreeSlopeGrounds() {
        MovementFixtures.Frame frame = new MovementFixtures.Frame(0, 0, 0, 0);
        List<BrushPlane> planes = MovementFixtures.rampPlanes(frame, 30, 6, -5, 5);
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.brush(planes, 0.01)));
        double dt = MovementFixtures.dt(SURF);
        double[] origin = MovementFixtures.originOnSlope(frame, 30, 3, 0, 60);
        CsPlayerState s = new CsPlayerState();
        s.origin.set(origin[0], origin[1], origin[2]);
        CsMoveInput idle = MovementFixtures.input(0, 0, false, 0);
        int landedAt = -1;
        for (int i = 0; i < 160; i++) {
            CsGameMovement.step(s, idle, SURF, world, dt);
            if (s.landedThisStep) {
                landedAt = i;
                break;
            }
            assertFalse(s.grounded);
        }
        assertTrue(landedAt > 0);
        assertTrue(s.grounded);
        assertFalse(s.surfing);
        assertEquals(Math.cos(Math.toRadians(30)), s.groundNormalY, 1e-9);
        // Standing on a walkable slope does not slide.
        double x = s.origin.x;
        for (int i = 0; i < 80; i++) {
            CsGameMovement.step(s, idle, SURF, world, dt);
            assertTrue(s.grounded);
        }
        assertEquals(x, s.origin.x, 0.1);
    }
}
