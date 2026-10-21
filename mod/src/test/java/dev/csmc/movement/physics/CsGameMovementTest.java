package dev.csmc.movement.physics;

import static dev.csmc.movement.physics.MovementFixtures.HALF_WIDTH;
import static dev.csmc.movement.physics.MovementFixtures.HEIGHT;
import static dev.csmc.movement.physics.MovementFixtures.KNIFE_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Ground and air behaviour of {@link CsGameMovement} against values derived from the cited Source SDK 2013
 * {@code gamemovement.cpp} formulas and CS:GO cvar defaults (see docs/dev/movement-reference.md).
 */
public class CsGameMovementTest {
    private static final CsMovementParams COMP = CsMovementParams.COMPETITIVE;
    private static final CsMovementParams BHOP = CsMovementParams.BHOP;

    private static ConvexBrushWorld flat() {
        return new ConvexBrushWorld(List.of(MovementFixtures.floor()));
    }

    private static double horizontal(CsPlayerState s) {
        return s.velocity.horizontalLength();
    }

    @Test
    public void presetsSubstepAtTheirTickRates() {
        assertEquals(3, COMP.substepsPerTick());
        assertEquals(6, BHOP.substepsPerTick());
        assertEquals(4, CsMovementParams.SURF.substepsPerTick());
    }

    /**
     * Discrete Source jump: the jump step applies StartGravity, the impulse and CheckJumpButton's FinishGravity before
     * moving, so move k uses {@code I - k g dt}; apex = sum of positive moves. Continuous apex I^2 / 2g = 57.0 units.
     */
    @Test
    public void flatGroundJumpApexMatchesTheSourceIntegration() {
        double dt = MovementFixtures.dt(COMP);
        double impulse = COMP.jumpImpulse();
        double g = COMP.gravity();
        double expected = 0;
        for (int k = 1; impulse - k * g * dt > 0; k++) {
            expected += (impulse - k * g * dt) * dt;
        }
        CsPlayerState s = MovementFixtures.standing();
        ConvexBrushWorld world = flat();
        double apex = 0;
        boolean jumped = false;
        int airSteps = 0;
        for (int i = 0; i < 120; i++) {
            CsGameMovement.step(s, MovementFixtures.input(0, 0, i == 0, 0), COMP, world, dt);
            jumped |= s.jumpedThisStep;
            apex = Math.max(apex, s.origin.y);
            if (!s.grounded) airSteps++;
        }
        assertTrue(jumped);
        assertEquals(expected, apex, 1e-9);
        assertEquals(impulse * impulse / (2 * g), 57.0, 0.01);
        assertEquals(57.0, apex, 3.0);
        assertTrue(s.grounded);
        // Air time ~ 2 I / g = 0.755 s.
        assertEquals(2 * impulse / g, airSteps * dt, 3 * dt);
    }

    @Test
    public void groundAccelerationReachesButNeverExceedsMaxSpeed() {
        CsPlayerState s = MovementFixtures.standing();
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        double first = -1;
        for (int i = 0; i < 120; i++) {
            CsGameMovement.step(s, MovementFixtures.input(450, 0, false, 0), COMP, world, dt);
            double speed = horizontal(s);
            assertTrue("speed " + speed + " above max", speed <= KNIFE_SPEED + 1e-9);
            assertTrue(s.grounded);
            assertEquals(0.0, s.velocity.y, 0.0);
            if (first < 0 && speed >= KNIFE_SPEED - 1e-9) first = i * dt;
        }
        // Continuous model: v' = accel * 250 - friction * v, v(t) = (5.5*250/5.2)(1 - e^{-5.2 t}); 250 at ~0.56 s.
        assertTrue("reached max at " + first, first > 0.3 && first < 0.8);
        assertEquals(KNIFE_SPEED, s.velocity.z, 1e-9); // yaw 0 faces +z
        assertEquals(0.0, s.velocity.x, 1e-9);
    }

    @Test
    public void frictionStopsARunnerInTheSourceStopTime() {
        CsPlayerState s = MovementFixtures.standing();
        s.velocity.set(0, 0, KNIFE_SPEED);
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        // Exponential decay at rate friction above stopspeed, then linear at stopspeed * friction:
        double expected = Math.log(KNIFE_SPEED / COMP.stopSpeed()) / COMP.friction()
                + COMP.stopSpeed() / (COMP.stopSpeed() * COMP.friction());
        double stoppedAt = -1;
        for (int i = 0; i < 120 && stoppedAt < 0; i++) {
            CsGameMovement.step(s, MovementFixtures.input(0, 0, false, 0), COMP, world, dt);
            if (horizontal(s) == 0.0) stoppedAt = (i + 1) * dt;
        }
        assertEquals(expected, stoppedAt, 0.05);
    }

    @Test
    public void duckAndWalkCapGroundSpeed() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        double[] caps = {KNIFE_SPEED * 0.34, KNIFE_SPEED * 0.52, KNIFE_SPEED * 0.34};
        boolean[][] poses = {{true, false}, {false, true}, {true, true}};
        for (int p = 0; p < 3; p++) {
            CsPlayerState s = MovementFixtures.standing();
            double max = 0;
            for (int i = 0; i < 120; i++) {
                CsGameMovement.step(s, MovementFixtures.input(450, 450, false, poses[p][0], poses[p][1], 0), COMP,
                        world, dt);
                max = Math.max(max, horizontal(s));
            }
            assertEquals(caps[p], max, 1e-9);
            assertEquals(caps[p], horizontal(s), 1e-9);
        }
    }

    @Test
    public void forwardOnlyAirMovementCannotExceedRunSpeed() {
        ConvexBrushWorld world = new ConvexBrushWorld(List.of());
        double dt = MovementFixtures.dt(BHOP);
        CsPlayerState s = new CsPlayerState();
        s.origin.set(0, 10000, 0);
        s.velocity.set(0, 0, 200);
        for (int i = 0; i < 600; i++) {
            CsGameMovement.step(s, MovementFixtures.input(450, 0, false, 0), BHOP, world, dt);
            assertTrue(horizontal(s) <= KNIFE_SPEED + 1e-9);
        }
        // sv_air_max_wishspeed 30 lets W add speed only while v.wishdir < 30: from 200 it never adds.
        assertEquals(200, horizontal(s), 1e-9);
        // From standstill W only reaches the 30 u/s air wish cap.
        s.velocity.set(0, 0, 0);
        for (int i = 0; i < 600; i++) {
            CsGameMovement.step(s, MovementFixtures.input(450, 0, false, 0), BHOP, world, dt);
        }
        assertEquals(BHOP.airMaxWishSpeed(), horizontal(s), 1e-9);
    }

    /** Yaw that keeps the strafe wish direction perpendicular to the velocity (a scripted optimal turn). */
    private static double strafeYaw(CsPlayerState s) {
        return MovementFixtures.yawFacing(s.velocity.x, s.velocity.z);
    }

    @Test
    public void airStrafingWithAutoBunnyhopGainsSpeedOverTenHops() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(BHOP);
        CsPlayerState s = MovementFixtures.standing();
        s.velocity.set(0, 0, KNIFE_SPEED);
        int hops = 0;
        int groundSteps = 0;
        double side = 450;
        for (int i = 0; i < 20000 && hops < 10; i++) {
            CsGameMovement.step(s, MovementFixtures.input(0, side, true, strafeYaw(s)), BHOP, world, dt);
            if (s.jumpedThisStep) {
                hops++;
                side = -side; // alternate strafe direction each hop
            }
            if (s.grounded) groundSteps++;
        }
        assertEquals(10, hops);
        // Each air step adds the 30 u/s wish cap perpendicular to v: v^2 grows ~900 per step (~90 steps per hop).
        assertTrue("speed " + horizontal(s), horizontal(s) > 600);
        // Auto bunnyhop jumps on the first grounded step, so friction never runs: one grounded step per landing.
        assertTrue(groundSteps <= hops + 1);
    }

    @Test
    public void competitiveCapsSpeedAtEveryJump() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState s = MovementFixtures.standing();
        s.velocity.set(0, 0, KNIFE_SPEED);
        double cap = CsGameMovement.BUNNYJUMP_MAX_SPEED_FACTOR * CsGameMovement.BUNNYHOP_BASE_SPEED;
        assertEquals(286.0, cap, 1e-9);
        int hops = 0;
        double maxBeforeJump = 0;
        double side = 450;
        for (int i = 0; i < 20000 && hops < 10; i++) {
            boolean press = s.grounded && !s.jumpHeldLastStep; // manual: fresh press on landing
            double before = s.velocity.length();
            CsGameMovement.step(s, MovementFixtures.input(0, side, press, strafeYaw(s)), COMP, world, dt);
            if (s.jumpedThisStep) {
                hops++;
                side = -side;
                maxBeforeJump = Math.max(maxBeforeJump, before);
                // Capped before the impulse; AirMove in the same step may then add at most the 30 u/s air wish.
                assertTrue("post-jump speed " + horizontal(s),
                        horizontal(s) <= Math.hypot(cap, COMP.airMaxWishSpeed()) + 1e-9);
            }
        }
        assertEquals(10, hops);
        assertTrue("the cap must have engaged: " + maxBeforeJump, maxBeforeJump > cap);
    }

    @Test
    public void manualModeJumpNeedsAFreshPress() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState s = MovementFixtures.standing();
        int jumps = 0;
        for (int i = 0; i < 180; i++) { // 3 s holding jump
            CsGameMovement.step(s, MovementFixtures.input(0, 0, true, 0), COMP, world, dt);
            if (s.jumpedThisStep) jumps++;
        }
        assertEquals(1, jumps);
        assertTrue(s.grounded);
        CsGameMovement.step(s, MovementFixtures.input(0, 0, false, 0), COMP, world, dt);
        CsGameMovement.step(s, MovementFixtures.input(0, 0, true, 0), COMP, world, dt);
        assertTrue(s.jumpedThisStep);
        // The same hold with auto bunnyhopping re-jumps on every landing.
        CsPlayerState auto = MovementFixtures.standing();
        int autoJumps = 0;
        boolean landedLastStep = false;
        for (int i = 0; i < 360; i++) {
            CsGameMovement.step(auto, MovementFixtures.input(0, 0, true, 0), BHOP, world,
                    MovementFixtures.dt(BHOP));
            if (landedLastStep) assertTrue("held jump re-jumps on the first grounded step", auto.jumpedThisStep);
            landedLastStep = auto.landedThisStep;
            if (auto.jumpedThisStep) autoJumps++;
        }
        // One hop lasts the ~0.755 s air time plus one grounded step: 4 to 5 hops in 3 s.
        assertTrue("auto hops " + autoJumps, autoJumps >= 4 && autoJumps <= 5);
    }

    /**
     * Stamina approximation against the community measurement (GOKZ wiki: with sv_staminalandcost .050 the next jump
     * after a flat landing reaches ~40 instead of ~55 units).
     */
    @Test
    public void landingStaminaLowersTheNextJumpAndRecovers() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState s = MovementFixtures.standing();
        double apex1 = 0;
        double apex2 = 0;
        int jumps = 0;
        for (int i = 0; i < 200; i++) {
            boolean press = s.grounded && !s.jumpHeldLastStep && jumps < 2;
            CsGameMovement.step(s, MovementFixtures.input(0, 0, press, 0), COMP, world, dt);
            if (s.jumpedThisStep) jumps++;
            if (s.landedThisStep && jumps == 1) {
                assertEquals(COMP.staminaLandCost() * (COMP.jumpImpulse()), s.stamina, 3.0);
            }
            if (jumps == 1) apex1 = Math.max(apex1, s.origin.y);
            if (jumps == 2) apex2 = Math.max(apex2, s.origin.y);
        }
        assertEquals(2, jumps);
        assertEquals(54.5, apex1, 0.5);
        assertEquals(40.0, apex2, 3.0);
        assertEquals(0.0, s.stamina, 0.0);
    }

    @Test
    public void halfBlockStepClimbsAndThreeQuarterBlockDoesNot() {
        double dt = MovementFixtures.dt(COMP);
        for (double height : new double[] {0.5, 0.75}) {
            ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.floor(),
                    MovementFixtures.box(-5, 0, 2, 5, height, 40)));
            CsPlayerState s = MovementFixtures.standing();
            for (int i = 0; i < 120; i++) {
                CsGameMovement.step(s, MovementFixtures.input(450, 0, false, 0), COMP, world, dt);
            }
            if (height == 0.5) {
                assertEquals(20.0, s.origin.y, 0.05);
                assertTrue(s.origin.z > 2 * 40 + HALF_WIDTH);
                assertTrue(s.grounded);
            } else {
                assertEquals(0.0, s.origin.y, 0.05);
                assertEquals(2 * 40 - HALF_WIDTH, s.origin.z, 0.1);
            }
        }
    }

    @Test
    public void rightAngleCornerCreaseStopsWithoutJitter() {
        // Walls along x = 2 (facing -x) and z = 2 (facing -z): a 90 degree corner ahead-left at yaw -45.
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.floor(),
                MovementFixtures.box(2, 0, -10, 3, 3, 10), MovementFixtures.box(-10, 0, 2, 10, 3, 3)));
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState s = MovementFixtures.standing();
        double yaw = MovementFixtures.yawFacing(1, 1);
        double lastX = 0, lastZ = 0;
        for (int i = 0; i < 180; i++) {
            CsGameMovement.step(s, MovementFixtures.input(450, 0, false, yaw), COMP, world, dt);
            assertTrue(s.origin.x <= 80 - HALF_WIDTH + 1e-9 && s.origin.z <= 80 - HALF_WIDTH + 1e-9);
            if (i >= 120) {
                assertEquals(lastX, s.origin.x, 1e-9);
                assertEquals(lastZ, s.origin.z, 1e-9);
            }
            lastX = s.origin.x;
            lastZ = s.origin.z;
        }
        assertEquals(80 - HALF_WIDTH, s.origin.x, 0.1);
        assertEquals(80 - HALF_WIDTH, s.origin.z, 0.1);
        assertTrue(s.grounded);
    }

    @Test
    public void ceilingClipsUpwardVelocity() {
        // Ceiling 2 blocks up leaves 8 units of headroom over the 72-unit hull.
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.floor(),
                MovementFixtures.box(-10, 2, -10, 10, 3, 10)));
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState s = MovementFixtures.standing();
        double stopY = 80 - HEIGHT - ConvexBrushWorld.DIST_EPSILON;
        boolean clipped = false;
        for (int i = 0; i < 60; i++) {
            double vyBefore = s.velocity.y;
            CsGameMovement.step(s, MovementFixtures.input(0, 0, i == 0, 0), COMP, world, dt);
            assertTrue(s.origin.y + HEIGHT <= 80 + 1e-9);
            if (!clipped && s.origin.y >= stopY - 1e-3) {
                // Stopped one DIST_EPSILON under the ceiling while still rising: ClipVelocity removed vy.
                clipped = true;
                assertTrue(i == 0 || vyBefore > 100);
                assertTrue("vy after ceiling " + s.velocity.y, s.velocity.y <= 0.0);
            }
        }
        assertTrue(clipped);
        assertTrue(s.grounded);
    }

    @Test
    public void velocityIsClampedPerAxisToMaxVelocity() {
        ConvexBrushWorld world = new ConvexBrushWorld(List.of());
        CsPlayerState s = new CsPlayerState();
        s.velocity.set(5000, -9000, Double.NaN);
        CsGameMovement.step(s, MovementFixtures.input(0, 0, false, 0), COMP, world, MovementFixtures.dt(COMP));
        assertEquals(COMP.maxVelocity(), s.velocity.x, 0.0);
        assertEquals(-COMP.maxVelocity(), s.velocity.y, 0.0);
        assertEquals(0.0, s.velocity.z, 0.0);
    }

    @Test
    public void risingFasterThanNonJumpVelocityIsNeverGrounded() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState fast = new CsPlayerState();
        fast.origin.set(0, 0.5, 0);
        fast.velocity.set(0, 160, 0);
        CsGameMovement.step(fast, MovementFixtures.input(0, 0, false, 0), COMP, world, dt);
        assertFalse(fast.grounded);
        CsPlayerState slow = new CsPlayerState();
        slow.origin.set(0, 0.5, 0);
        slow.velocity.set(0, 20, 0);
        CsGameMovement.step(slow, MovementFixtures.input(0, 0, false, 0), COMP, world, dt);
        assertTrue(slow.grounded);
        assertTrue(slow.landedThisStep);
        assertEquals(0.0, slow.velocity.y, 0.0);
        // categorizePosition alone honours the same rule.
        CsPlayerState external = MovementFixtures.standing();
        external.velocity.set(0, 300, 0);
        CsGameMovement.categorizePosition(external, MovementFixtures.input(0, 0, false, 0), world);
        assertFalse(external.grounded);
    }

    @Test
    public void groundSeamsBetweenBlocksCostNoSpeed() {
        List<dev.csmc.movement.geometry.ConvexBrush> blocks = new java.util.ArrayList<>();
        for (int z = -2; z < 40; z++) {
            for (int x = -2; x <= 2; x++) {
                blocks.add(MovementFixtures.box(x, -1, z, x + 1, 0, z + 1));
            }
        }
        ConvexBrushWorld world = new ConvexBrushWorld(blocks);
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState s = MovementFixtures.standing();
        s.origin.set(20, 0, 0);
        s.velocity.set(0, 0, KNIFE_SPEED);
        for (int i = 0; i < 180; i++) {
            CsGameMovement.step(s, MovementFixtures.input(450, 0, false, 0), COMP, world, dt);
            assertEquals(KNIFE_SPEED, s.velocity.z, 1e-9);
            // Feet stay on the floor; StayOnGround parks them one DIST_EPSILON above it, Source-style.
            assertEquals(ConvexBrushWorld.DIST_EPSILON, s.origin.y, ConvexBrushWorld.DIST_EPSILON + 1e-6);
        }
        assertEquals(KNIFE_SPEED * 180 * dt, s.origin.z, 1e-6);
    }

    /**
     * sv_enablebunnyhopping 0: at the jump, the whole velocity (horizontal plus StartGravity's -g dt / 2) is scaled
     * to 1.1 x 260 = 286 u/s before the impulse; sv_enablebunnyhopping 1 keeps it.
     */
    @Test
    public void jumpCapScalesVelocityToHundredTenPercentOfRunSpeed() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState capped = MovementFixtures.standing();
        capped.velocity.set(0, 0, 400);
        CsGameMovement.step(capped, MovementFixtures.input(0, 0, true, 0), COMP, world, dt);
        assertTrue(capped.jumpedThisStep);
        double halfGravity = COMP.gravity() * 0.5 * dt;
        double expected = 400 * 286.0 / Math.hypot(400, halfGravity);
        assertEquals(expected, capped.velocity.z, 1e-9);

        CsPlayerState free = MovementFixtures.standing();
        free.velocity.set(0, 0, 400);
        CsGameMovement.step(free, MovementFixtures.input(0, 0, true, 0), BHOP, world, MovementFixtures.dt(BHOP));
        assertTrue(free.jumpedThisStep);
        assertEquals(400, free.velocity.z, 1e-9);
    }

    /**
     * Stamina approximation on the ground: after a flat landing at run speed the runner slows (GOKZ wiki measured
     * 250 -> 183.05 u/s at sv_staminalandcost .050) and returns to 250 u/s once stamina has recovered at 60/s.
     */
    @Test
    public void landingStaminaSlowsARunnerThenRecovers() {
        ConvexBrushWorld world = flat();
        double dt = MovementFixtures.dt(COMP);
        CsPlayerState s = MovementFixtures.standing();
        s.velocity.set(0, 0, KNIFE_SPEED);
        double min = Double.POSITIVE_INFINITY;
        boolean landed = false;
        for (int i = 0; i < 180; i++) {
            CsGameMovement.step(s, MovementFixtures.input(450, 0, i == 0, 0), COMP, world, dt);
            landed |= s.landedThisStep;
            if (landed) min = Math.min(min, horizontal(s));
        }
        assertTrue(landed);
        assertTrue("slowest " + min, min < 200 && min > 140);
        assertEquals(0.0, s.stamina, 0.0);
        assertEquals(KNIFE_SPEED, horizontal(s), 1e-9);
    }
}
