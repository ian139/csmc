package dev.csmc.thirdperson.melee;

/** Procedural player-skeleton silhouettes, not retargeted first-person CS2 arm clips. */
public final class KnifePoseCurves {
    public enum Action { IDLE, DRAW, INSPECT, SLASH, STAB, BACKSTAB }
    public static final class Pose {
        public float rightArmX, rightArmY, rightArmZ, leftArmX, leftArmY, leftArmZ;
        public float rightArmWeight, leftArmWeight;
    }

    private KnifePoseCurves() {}

    public static Action action(String action) {
        return switch (action) {
            case "deploy", "draw" -> Action.DRAW;
            case "inspect" -> Action.INSPECT;
            case "slash", "slash_hit", "slash_backstab" -> Action.SLASH;
            case "stab", "stab_hit" -> Action.STAB;
            case "backstab" -> Action.BACKSTAB;
            default -> Action.IDLE;
        };
    }

    public static double duration(Action action) {
        return switch (action) {
            case DRAW -> 0.5;
            case INSPECT -> 2.5;
            case SLASH -> 0.4;
            case STAB -> 0.6;
            case BACKSTAB -> 0.75;
            case IDLE -> 0.0;
        };
    }

    /** Fills a reusable pose; expiration and cancellation both return exactly to the ready grip. */
    public static void sample(Action action, double elapsed, double duration, float aim, boolean dual, Pose out) {
        out.rightArmWeight = out.leftArmWeight = 1.0F;
        out.rightArmX = -0.72F + aim * 0.55F;
        out.rightArmY = -0.16F;
        out.rightArmZ = 0.12F;
        out.leftArmX = -0.48F + aim * 0.25F;
        out.leftArmY = 0.18F;
        out.leftArmZ = -0.18F;
        if (elapsed >= 0 && elapsed < duration && duration > 0) {
            float t = (float) (elapsed / duration);
            switch (action) {
                case DRAW -> {
                    float lower = 1 - ease(t);
                    out.rightArmX += 1.0F * lower;
                    out.rightArmZ += 0.38F * lower;
                }
                case INSPECT -> {
                    float lift = Math.min(ease(t / 0.18F), ease((1 - t) / 0.20F));
                    out.rightArmX -= 0.70F * lift;
                    out.rightArmY -= 0.55F * lift;
                    out.rightArmZ -= (0.35F + 0.18F * (float) Math.sin(t * Math.PI * 4)) * lift;
                }
                case SLASH -> {
                    float wind = ease(t / 0.22F);
                    float sweep = ease((t - 0.22F) / 0.34F);
                    float recover = 1 - ease((t - 0.56F) / 0.44F);
                    out.rightArmX -= (0.30F * wind + 0.35F * sweep) * recover;
                    out.rightArmY += (-0.85F * wind + 2.05F * sweep) * recover;
                    out.rightArmZ += (0.42F * wind - 0.85F * sweep) * recover;
                    out.leftArmX -= 0.18F * wind * recover;
                }
                case STAB, BACKSTAB -> {
                    float wind = ease(t / 0.25F);
                    float drive = ease((t - 0.25F) / 0.23F);
                    float recover = 1 - ease((t - 0.48F) / 0.52F);
                    float force = action == Action.BACKSTAB ? 1.2F : 1.0F;
                    out.rightArmX += (0.35F * wind - 1.14F * drive) * recover * force;
                    out.rightArmY -= 0.16F * wind * recover;
                    out.rightArmZ -= 0.14F * drive * recover;
                }
                default -> { }
            }
        }
        if (dual) {
            out.leftArmX = out.rightArmX;
            out.leftArmY = -out.rightArmY;
            out.leftArmZ = -out.rightArmZ;
        }
    }

    private static float ease(float value) {
        float x = Math.max(0, Math.min(1, value));
        return x * x * (3 - 2 * x);
    }
}
