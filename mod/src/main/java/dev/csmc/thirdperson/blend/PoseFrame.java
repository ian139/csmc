package dev.csmc.thirdperson.blend;

/**
 * Additive upper-body pose sample for one rendered player, in radians, with no Minecraft dependencies. Same fields as
 * the client {@code PlayerPose}: arm angles REPLACE vanilla arm rotations by {@code armWeight} (0 = vanilla arms,
 * 1 = this pose); head/body offsets are added. Mutable and reused per frame.
 */
public final class PoseFrame {
    public float rightArmX, rightArmY, rightArmZ;
    public float leftArmX, leftArmY, leftArmZ;
    /** 0..1 blend of the arm angles above over vanilla's arm rotations. */
    public float rightArmWeight, leftArmWeight;
    public float bodyYOffset, headXOffset;

    public void clear() {
        rightArmX = rightArmY = rightArmZ = leftArmX = leftArmY = leftArmZ = 0F;
        rightArmWeight = leftArmWeight = 0F;
        bodyYOffset = headXOffset = 0F;
    }
}
