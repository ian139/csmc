package dev.csmc.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.csmc.data.WeaponData;
import dev.csmc.movement.physics.CsMovementParams;
import dev.csmc.movement.physics.CsUnits;
import org.junit.Test;

/** The per-tick collision-collection radius stays small at the highest accepted held speed. */
public class CollisionReachTest {
    @Test
    public void stationaryReachAtUpperSpeedBoundIsBounded() {
        double step = CsUnits.toUnits(0.6);
        double duck = CsUnits.toUnits(0.3);
        for (CsMovementParams preset : new CsMovementParams[] {CsMovementParams.COMPETITIVE, CsMovementParams.BHOP,
                CsMovementParams.SURF}) {
            double reach = MovementResolve.collisionReach(0.0, WeaponData.MAX_ALLOWED_SPEED, preset, step, duck);
            // 2 × 2000 u/s over 1/20 s = 200 u (CsUnits.toBlocks), plus jump, gravity, step, duck and margin.
            assertTrue("reach " + reach, reach < 18.0);
            double cells = Math.pow(2.0 * reach + 1.0, 3);
            assertTrue("cells per tick " + cells, cells < 60_000.0);
        }
    }

    @Test
    public void reachMatchesTheMovementBudgetFormula() {
        CsMovementParams p = CsMovementParams.SURF;
        double expected = CsUnits.toBlocks((100.0 + 2.0 * 250.0 + p.jumpImpulse() + p.gravity())
                / CsUnits.TICKS_PER_SECOND + 10.0 + 5.0) + 0.25;
        assertEquals(expected, MovementResolve.collisionReach(100.0, 250.0, p, 10.0, 5.0), 1.0E-12);
    }
}
