package dev.csmc.melee;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * A per-weapon first-person override expressed relative to the built-in default, for a camera-space viewmodel
 * pivoted at the hand: {@code T(t_c − t_d) · R_c · R_d⁻¹ · S(s_c / s_d)}. Applied inside the hand-pivot sandwich
 * ({@code T(anchor) · adjustment · T(−anchor)}), a configuration equal to the default is the identity and a
 * scale-only change scales about the hand. Transforms are {@code {x, y, z, rx, ry, rz, scale}} with rotations in
 * degrees (XYZ order); a left hand mirrors x, ry and rz like the hand transforms do. Allocation-free.
 */
public final class ViewmodelAdjustment {
    private ViewmodelAdjustment() {}

    /** Writes the adjustment to {@code out}; {@code a}/{@code b} are scratch. */
    public static Matrix4f adjustment(float[] configured, float[] defaults, boolean leftHand, Matrix4f out,
                                      Quaternionf a, Quaternionf b) {
        float mirror = leftHand ? -1 : 1;
        rotation(configured, mirror, a);
        rotation(defaults, mirror, b).conjugate();
        return out.translation((configured[0] - defaults[0]) * mirror, configured[1] - defaults[1],
                        configured[2] - defaults[2])
                .rotate(a.mul(b))
                .scale(configured[6] / defaults[6]);
    }

    private static Quaternionf rotation(float[] transform, float mirror, Quaternionf out) {
        return out.rotationXYZ((float) Math.toRadians(transform[3]), (float) Math.toRadians(transform[4] * mirror),
                (float) Math.toRadians(transform[5] * mirror));
    }
}
