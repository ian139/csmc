package dev.csmc.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

/**
 * Procedural knife actions for models without an exported clip. Applied in model space right after the display
 * transform, where every hand context shares the glTF convention (+Y up, blade along +Z, pivot on the grip), so one
 * motion reads correctly in first and third person. In first person the model is turned around, so model +X is the
 * viewer's left: the slash sweeps the tip from the right of the screen to the left.
 */
final class KnifeMotion {
    private static final float DRAW_SECONDS = 0.5f;
    private static final float SLASH_SECONDS = 0.4f;
    private static final float STAB_SECONDS = 0.6f;
    private static final float BACKSTAB_SECONDS = 0.75f;
    /** Third-person swings are smaller, so the blade stays near the body model's hand. */
    private static final float THIRD_PERSON = 0.6f;
    private static final Quaternionf ROTATION = new Quaternionf();

    private KnifeMotion() {}

    /** Procedural length of an action (hit/backstab clip variants swing like their base), or 0 when it has none. */
    static float duration(String action) {
        return switch (dev.csmc.melee.KnifeClipChooser.baseAction(action)) {
            case "draw" -> DRAW_SECONDS;
            case "slash" -> SLASH_SECONDS;
            case "stab" -> STAB_SECONDS;
            case "backstab" -> BACKSTAB_SECONDS;
            default -> 0;
        };
    }

    static boolean handles(String action) {
        return duration(action) > 0;
    }

    /** @param progress 0..1 through the action */
    static void apply(PoseStack poses, String action, float progress, boolean firstPerson, boolean left) {
        float t = Math.max(0, Math.min(1, progress));
        float strength = firstPerson ? 1 : THIRD_PERSON;
        float side = left ? -1 : 1;
        switch (action) {
            case "draw" -> draw(poses, t, strength);
            case "slash" -> slash(poses, t, strength, side);
            case "stab" -> thrust(poses, t, strength, 1);
            case "backstab" -> thrust(poses, t, strength, 1.35f);
            default -> { }
        }
    }

    /** Rises into view while flipping one full turn forward over the grip. */
    private static void draw(PoseStack poses, float t, float strength) {
        float rest = 1 - ease(t);
        poses.translate(0, -0.35f * rest * strength, 0);
        poses.mulPose(ROTATION.rotationX((float) Math.toRadians(-360 * rest)));
    }

    /** Wind up to the right, sweep flat across to the left with a slight lunge, recover. */
    private static void slash(PoseStack poses, float t, float strength, float side) {
        float yaw, x, z = 0, roll;
        if (t < 0.2f) {
            float s = ease(t / 0.2f);
            yaw = -40 * s;
            x = -0.12f * s;
            roll = 25 * s;
        } else if (t < 0.55f) {
            float s = ease((t - 0.2f) / 0.35f);
            yaw = -40 + 110 * s;
            x = -0.12f + 0.45f * s;
            z = 0.2f * (float) Math.sin(Math.PI * s);
            roll = 25 + 45 * s;
        } else {
            float rest = 1 - ease((t - 0.55f) / 0.45f);
            yaw = 70 * rest;
            x = 0.33f * rest;
            roll = 70 * rest;
        }
        poses.translate(x * side * strength, 0, z * strength);
        poses.mulPose(ROTATION.rotationYXZ((float) Math.toRadians(yaw * side * strength), 0,
                (float) Math.toRadians(roll * side * strength)));
    }

    /** Draw back and up, drive straight forward, return; {@code reach} scales the heavier backstab. */
    private static void thrust(PoseStack poses, float t, float strength, float reach) {
        float z, pitch;
        if (t < 0.25f) {
            float s = ease(t / 0.25f);
            z = -0.1f * s;
            pitch = -12 * s;
        } else if (t < 0.5f) {
            float s = ease((t - 0.25f) / 0.25f);
            z = -0.1f + 0.55f * s;
            pitch = -12 + 16 * s;
        } else {
            float rest = 1 - ease((t - 0.5f) / 0.5f);
            z = 0.45f * rest;
            pitch = 4 * rest;
        }
        poses.translate(0, 0.04f * pitch / 12 * strength, z * reach * strength);
        poses.mulPose(ROTATION.rotationX((float) Math.toRadians(pitch * strength)));
    }

    private static float ease(float x) {
        return x * x * (3 - 2 * x);
    }
}
