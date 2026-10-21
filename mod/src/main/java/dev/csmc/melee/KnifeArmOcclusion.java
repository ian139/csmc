package dev.csmc.melee;

import java.util.Arrays;

/**
 * Body-geometry occlusion for first-person arms: a worn sleeve hides the bare arm beneath it. CS2 drops those
 * triangles rather than relying on depth, since the bare arm otherwise pokes through the sleeve where the two skins
 * deform differently. Pure index filtering, run once while preparing the arms.
 */
public final class KnifeArmOcclusion {
    private KnifeArmOcclusion() {}

    /** True for joints a sleeve covers: upper/lower arm segments and their twist bones, not hands or fingers. */
    public static boolean sleeveCovers(String joint) {
        return joint != null && (joint.startsWith("arm_upper") || joint.startsWith("arm_lower")
                || joint.startsWith("armUpper") || joint.startsWith("clavicle"));
    }

    /**
     * {@code indices} without the triangles whose three vertices are all dominated (largest weight) by a covered
     * joint slot. {@code joints}/{@code weights} hold four slots per vertex.
     */
    public static int[] uncovered(int[] indices, int[] joints, float[] weights, boolean[] coveredSlot) {
        int[] kept = new int[indices.length];
        int count = 0;
        for (int t = 0; t + 2 < indices.length; t += 3) {
            if (covered(indices[t], joints, weights, coveredSlot) && covered(indices[t + 1], joints, weights, coveredSlot)
                    && covered(indices[t + 2], joints, weights, coveredSlot)) {
                continue;
            }
            kept[count++] = indices[t];
            kept[count++] = indices[t + 1];
            kept[count++] = indices[t + 2];
        }
        return count == indices.length ? indices : Arrays.copyOf(kept, count);
    }

    private static boolean covered(int vertex, int[] joints, float[] weights, boolean[] coveredSlot) {
        int best = -1;
        float weight = 0;
        for (int k = 0; k < 4; k++) {
            if (weights[vertex * 4 + k] > weight) {
                weight = weights[vertex * 4 + k];
                best = joints[vertex * 4 + k];
            }
        }
        return best >= 0 && best < coveredSlot.length && coveredSlot[best];
    }
}
