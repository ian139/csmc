package dev.csmc.client.thirdperson;

/**
 * Additive upper-body pose for one rendered player, in radians, applied after vanilla {@code HumanoidModel.setupAnim}
 * (so vanilla walk, crouch, swim and jump leg/body motion is kept). Arm angles REPLACE vanilla arm rotations by
 * {@code armWeight} (0 = vanilla arms, 1 = this pose); head/body offsets are added. Mutable and reused per frame.
 *
 * <p>Provenance: CS2 exports contain no third-person player animation sets (only weapon world-model clips and
 * first-person arms), so every player-skeleton pose here is a procedural approximation of CS third-person behaviour.
 */
public final class PlayerPose {
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
