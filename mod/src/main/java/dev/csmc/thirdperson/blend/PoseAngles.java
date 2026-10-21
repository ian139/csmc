package dev.csmc.thirdperson.blend;

/** Final composition of the continuous, per-entity angle branch maintained by {@link PoseSmoother}. */
public final class PoseAngles {
    private PoseAngles() {}

    /**
     * Interpolates in unwrapped space. Do not wrap the pose against the current vanilla angle here: a moving vanilla
     * angle can cross the pose's opposite direction and flip that branch by a full turn while the weight is fractional.
     * The smoother already seeds its angle from vanilla, follows the nearest target branch continuously, and clamps
     * the supplied weight to 0..1.
     */
    public static float blend(float vanilla, float unwrappedPose, float weight) {
        return vanilla + weight * (unwrappedPose - vanilla);
    }
}
