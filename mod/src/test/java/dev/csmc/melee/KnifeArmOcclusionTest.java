package dev.csmc.melee;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KnifeArmOcclusionTest {
    // Slot 0: arm_lower_L_TWIST1 (covered), slot 1: hand_L.
    private static final boolean[] COVERED = {true, false};
    // Vertices 0-2 forearm, 3 hand-dominated, 4 forearm.
    private static final int[] JOINTS = {0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0};
    private static final float[] WEIGHTS = {.8f, .2f, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, .9f, .1f, 0, 0, 1, 0, 0, 0};

    @Test
    public void dropsOnlyFullyCoveredTriangles() {
        int[] kept = KnifeArmOcclusion.uncovered(new int[] {0, 1, 2, 2, 3, 4}, JOINTS, WEIGHTS, COVERED);
        assertArrayEquals(new int[] {2, 3, 4}, kept);
    }

    @Test
    public void untouchedWhenNothingCovered() {
        int[] indices = {2, 3, 4};
        assertSame(indices, KnifeArmOcclusion.uncovered(indices, JOINTS, WEIGHTS, COVERED));
    }

    @Test
    public void sleeveCoversArmSegmentsOnly() {
        assertTrue(KnifeArmOcclusion.sleeveCovers("arm_lower_R_TWIST1"));
        assertTrue(KnifeArmOcclusion.sleeveCovers("arm_upper_L"));
        assertFalse(KnifeArmOcclusion.sleeveCovers("hand_L"));
        assertFalse(KnifeArmOcclusion.sleeveCovers("finger_index_0_R"));
    }
}
