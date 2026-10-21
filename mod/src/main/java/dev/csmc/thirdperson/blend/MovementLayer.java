package dev.csmc.thirdperson.blend;

/** Pure procedural movement curves. Angles are radians; no Minecraft client classes are referenced. */
public final class MovementLayer {
    private static final double SETTLE_SECONDS = 0.18;

    private MovementLayer() {}

    public static void applyOffsets(PoseFrame pose, boolean airborne, boolean crouching, double verticalSpeed,
            float limbSwing, float limbSwingAmount, double secondsSinceLanding) {
        float x = crouching ? 0.28F : 0F;
        if (airborne) {
            x -= 0.08F + (float) Math.min(Math.abs(verticalSpeed) * 0.12, 0.10);
        } else if (secondsSinceLanding >= 0.0 && secondsSinceLanding < SETTLE_SECONDS) {
            double phase = secondsSinceLanding / SETTLE_SECONDS;
            x += (float) (0.10 * Math.sin(Math.PI * phase) * (1.0 - phase));
        }
        pose.rightArmX += x;
        pose.leftArmX += x;
        if (!airborne) {
            float amount = Math.max(0F, Math.min(1F, limbSwingAmount));
            float bob = (float) Math.cos(limbSwing * 0.6662F) * amount;
            pose.rightArmX += bob * 0.07F;
            pose.leftArmX -= bob * 0.07F;
            pose.rightArmZ += bob * 0.025F;
            pose.leftArmZ -= bob * 0.025F;
        }
    }
}
