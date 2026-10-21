package dev.csmc.thirdperson.melee;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

/**
 * Procedural butterfly-knife part flips for the third-person world model (CS2 exports only static world-model poses
 * for the butterfly's parts). Angles are hinge rotations in radians about the parts' shared pin axis, each about its
 * own node pivot and relative to its parent part; all are exactly zero (bind) at the start and end of every action, so
 * expiration never snaps.
 */
public final class ButterflyFlipCurves {
    /** Part slots written by {@link #sample}: front handle (carries the blade), rear handle, rear-handle latch. */
    public static final int FRONT = 0, REAR = 1, LOCK = 2;
    /** Fraction of the action the rear handle trails the front one. */
    static final double REAR_LAG = 0.2;
    /** Latch swing while the rear handle is fully apart; it closes again as the handles meet. */
    static final double LATCH_SWING = Math.PI * 0.8;

    private ButterflyFlipCurves() {}

    /** Flip cycles (open and close) an action performs, or 0 when it leaves the parts at rest. */
    public static int cycles(KnifePoseCurves.Action action) {
        return switch (action) {
            case DRAW -> 1;
            case INSPECT -> 2;
            default -> 0;
        };
    }

    /**
     * Fills {@code out[FRONT]}, {@code out[REAR]} and {@code out[LOCK]}; returns false (and zeroes {@code out}) outside
     * the action, when the parts must stay exactly at bind.
     */
    public static boolean sample(KnifePoseCurves.Action action, double elapsed, double duration, float[] out) {
        int cycles = cycles(action);
        if (cycles == 0 || duration <= 0 || elapsed <= 0 || elapsed >= duration) {
            out[FRONT] = out[REAR] = out[LOCK] = 0;
            return false;
        }
        double progress = elapsed / duration;
        out[FRONT] = (float) (Math.PI * opening(progress * cycles));
        double rear = opening(Math.max(0, (progress - REAR_LAG) / (1 - REAR_LAG)) * cycles);
        out[REAR] = (float) (-Math.PI * rear);
        out[LOCK] = (float) (LATCH_SWING * rear);
        return true;
    }

    /**
     * Scene-space delta of a part hinged at {@code pivot} (its node origin in the sampled pose) about {@code axis}:
     * {@code parent · T(pivot) · R(angle) · T(-pivot)}, where {@code parent} is its parent part's delta (identity for
     * the root part). Applied on the left of a joint matrix, it carries every descendant joint rigidly with the part.
     */
    public static Matrix4f hinge(Matrix4fc parent, Vector3fc pivot, Vector3fc axis, float angle, Matrix4f out) {
        return out.set(parent).translate(pivot).rotate(angle, axis).translate(-pivot.x(), -pivot.y(), -pivot.z());
    }

    /** 0 → 1 (fully swung open) → 0 per unit, with zero velocity at each closed end. */
    static double opening(double t) {
        return (1 - Math.cos(2 * Math.PI * t)) * 0.5;
    }
}
