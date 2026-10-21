package dev.csmc.movement.physics;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

/**
 * Airborne {@code m_surfaceFriction} per Source SDK 2013 {@code CategorizePosition}: 0.25 only while rising at
 * {@code 0 < vy <= NON_JUMP_VELOCITY} without ground; friction stays 1 when rising faster or not rising. Air
 * acceleration scales with it: sv_airaccelerate 12 x wishspeed 250 x dt 1/60 = 50 u/s, capped to the 30 u/s air
 * wish speed, so friction 1 adds 30 u/s and friction 0.25 adds 12.5 u/s.
 */
public class AirSurfaceFrictionTest {
    private static final CsMovementParams COMP = CsMovementParams.COMPETITIVE;
    private static final double DT = 1.0 / 60.0;
    /** Half a gravity step: the categorisation velocity is the state velocity before FinishGravity. */
    private static final double HALF_GRAVITY_STEP = COMP.gravity() * 0.5 * DT;

    private static double forwardGain(double vy) {
        CsPlayerState state = new CsPlayerState();
        state.origin.set(0, 1000, 0);
        state.velocity.set(0, vy, 0);
        CsGameMovement.step(state, MovementFixtures.input(450, 0, false, 0), COMP,
                new ConvexBrushWorld(List.of()), DT);
        return state.velocity.z;
    }

    @Test
    public void fastAscentKeepsFullAirAcceleration() {
        assertEquals(30.0, forwardGain(200), 1e-9);
    }

    @Test
    public void slowAscentQuartersAirAcceleration() {
        assertEquals(12.5, forwardGain(140 - HALF_GRAVITY_STEP - 1e-6), 1e-9);
        assertEquals(12.5, forwardGain(0), 1e-9); // categorised while still rising by half a gravity step
    }

    @Test
    public void ascentJustAboveTheNonJumpVelocityKeepsFullAirAcceleration() {
        assertEquals(30.0, forwardGain(141 - HALF_GRAVITY_STEP), 1e-9);
    }

    @Test
    public void fallingOrApexKeepsFullAirAcceleration() {
        assertEquals(30.0, forwardGain(-HALF_GRAVITY_STEP), 1e-9);
        assertEquals(30.0, forwardGain(-100), 1e-9);
    }

    @Test
    public void airborneFrictionThresholds() {
        assertEquals(1.0, CsGameMovement.airborneSurfaceFriction(141), 0.0);
        assertEquals(1.0, CsGameMovement.airborneSurfaceFriction(Math.nextUp(140.0)), 0.0);
        assertEquals(0.25, CsGameMovement.airborneSurfaceFriction(140), 0.0);
        assertEquals(0.25, CsGameMovement.airborneSurfaceFriction(Double.MIN_VALUE), 0.0);
        assertEquals(1.0, CsGameMovement.airborneSurfaceFriction(0), 0.0);
        assertEquals(1.0, CsGameMovement.airborneSurfaceFriction(-50), 0.0);
    }
}
