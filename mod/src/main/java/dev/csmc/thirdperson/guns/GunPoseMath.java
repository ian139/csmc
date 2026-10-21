package dev.csmc.thirdperson.guns;

/** Authored CS-like silhouettes; independent of Minecraft client classes for behavioral tests. */
public final class GunPoseMath {
    public enum Grip {
        PISTOL(.22f, .15), RIFLE(.12f, .10), SMG(.09f, .08), SNIPER(.24f, 1.2), SHOTGUN(.28f, .9);
        public final float kick;
        public final double cycle;
        Grip(float kick, double cycle) { this.kick = kick; this.cycle = cycle; }
    }
    public enum Action { NONE, FIRE, RELOAD, DRAW, INSPECT }
    public static final class Pose {
        public float rightX, rightY, rightZ, leftX, leftY, leftZ;
    }
    private GunPoseMath() {}

    public static Grip grip(String id) {
        return switch (id) {
            case "glock", "hkp2000", "usp_silencer", "p250", "fiveseven", "tec9", "cz75a", "deagle", "elite", "revolver", "taser" -> Grip.PISTOL;
            case "mac10", "mp9", "mp7", "mp5sd", "ump45", "p90", "bizon" -> Grip.SMG;
            case "awp", "ssg08", "scar20", "g3sg1", "m14" -> Grip.SNIPER;
            case "nova", "xm1014", "mag7", "sawedoff" -> Grip.SHOTGUN;
            default -> Grip.RIFLE;
        };
    }

    /** Null represents cancellation (reload end/cancel or a held-slot change invalidating the cue). */
    public static Action action(String cue) {
        if (cue == null) return Action.NONE;
        return switch (cue) {
            case "fire" -> Action.FIRE;
            case "reload" -> Action.RELOAD;
            case "draw" -> Action.DRAW;
            case "inspect" -> Action.INSPECT;
            default -> Action.NONE;
        };
    }

    public static double duration(Action action, double serverReload, double cycle, double deploy) {
        return switch (action) {
            case FIRE -> cycle;
            case DRAW -> deploy;
            case INSPECT -> 2.5;
            case RELOAD -> serverReload;
            case NONE -> 0;
        };
    }


    /** ItemInHandLayer reverses local X via its 180-degree Y turn; compensate only the actual raised pose. */
    public static float heldItemCorrection(float armX, float headPitch, float weight) {
        float raised = Math.max(0, Math.min((float) (Math.PI / 2), headPitch - armX));
        return -raised * Math.max(0, Math.min(1, weight));
    }
    /** Both arms track pitch; the support hand crosses inwards onto the foregrip/pistol hand. */
    public static void sample(Grip grip, float pitch, boolean scoped, Action action, double progress, Pose out) {
        boolean pistol = grip == Grip.PISTOL;
        out.rightX = -1.46f + pitch;
        out.rightY = -.10f;
        out.rightZ = .035f;
        out.leftX = (pistol ? -1.48f : -1.60f) + pitch;
        out.leftY = pistol ? .53f : .63f;
        out.leftZ = -.10f;
        if (scoped && !pistol) {
            out.rightX -= .09f;
            out.leftX -= .06f;
            out.rightY -= .08f;
            out.leftY += .04f;
        }
        if (progress < 0 || progress >= 1) return;
        float t = (float) progress;
        switch (action) {
            case FIRE -> {
                float kick = recoil(t) * grip.kick;
                out.rightX -= kick;
                out.leftX -= kick * .85f;
            }
            case RELOAD -> {
                float mag = window(t, 0, .20f, .53f, .70f);
                float bolt = window(t, .70f, .79f, .86f, 1);
                out.rightX += .22f * window(t, 0, .12f, .86f, 1);
                out.rightZ += .12f * mag;
                out.leftX += (pistol ? .68f : .90f) * mag + .10f * bolt;
                out.leftY += .28f * mag + .42f * bolt;
                out.leftZ -= .22f * mag;
                out.leftX -= .30f * bolt;
            }
            case DRAW -> {
                float lower = 1 - smooth(t);
                out.rightX += .95f * lower;
                out.leftX += 1.2f * lower;
                out.leftY *= 1 - lower;
            }
            case INSPECT -> {
                float tilt = window(t, 0, .18f, .77f, 1);
                out.rightY -= .35f * tilt;
                out.rightZ -= .46f * tilt;
                out.rightX -= .13f * tilt;
                out.leftX += .38f * tilt;
                out.leftY -= .15f * tilt;
                out.leftZ -= .28f * tilt;
            }
            case NONE -> { }
        }
    }

    public static float recoil(float t) {
        return t < .12f ? smooth(t / .12f) : 1 - smooth((t - .12f) / .88f);
    }
    private static float window(float t, float start, float peak, float hold, float end) {
        if (t <= start || t >= end) return 0;
        return t < peak ? smooth((t - start) / (peak - start))
                : t <= hold ? 1 : 1 - smooth((t - hold) / (end - hold));
    }
    private static float smooth(float x) { return x * x * (3 - 2 * x); }
}
