package dev.csmc.client.thirdperson;

/**
 * Contributes a third-person pose for players holding a given item family. Exactly one provider claims a player per
 * frame: the first registered provider whose {@link #applies} is true. Implementations must be allocation-free.
 */
public interface PoseProvider {
    boolean applies(PoseContext context);

    /** Writes the pose into {@code out} (already cleared). */
    void pose(PoseContext context, PlayerPose out);
}
