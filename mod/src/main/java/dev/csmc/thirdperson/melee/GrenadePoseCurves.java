package dev.csmc.thirdperson.melee;

/**
 * Procedural third-person grenade poses as keyframed arm curves, free of Minecraft client classes so the timing is unit
 * tested; the client {@code GrenadePoseProvider} copies {@link Pose} into its player pose. Angles are model radians
 * for the main ("right") arm and the support ("left") arm; negative X raises an arm forward, -PI points it straight
 * up. Keys are interpolated with cubic Hermite tangents (zero at the ends), so motion passes smoothly through each key
 * instead of stopping at it.
 *
 * <p>Provenance: CS2 ships no third-person player clips; these silhouettes approximate CS third-person grenade play —
 * a forward ready grip, the support hand pulling the pin at the chest, the throwing arm cocked overhead, then an
 * overhand whip (attack), an underhand lob (use) or a side-arm medium toss (both), recovering to the ready grip.
 */
public final class GrenadePoseCurves {
    /** The grenade action driving a player, mapped from the client action timeline. */
    public enum Stage {
        /** No grenade action, an expired one, or a cancel: the ready grip. */
        READY,
        /** Primed and held: pin pull, then the cocked arm, persisting until throw or cancel. */
        PRIME,
        /** Released: strength-specific throw and recovery. */
        THROW
    }

    /** Mutable output, reused per frame. Arm weights blend the angles over vanilla arms (0..1). */
    public static final class Pose {
        public float rightArmX, rightArmY, rightArmZ;
        public float leftArmX, leftArmY, leftArmZ;
        public float bodyYOffset;
        public float weight;
    }

    /** Contract duration of a grenade throw action (release plus recovery). */
    public static final double THROW_SECONDS = 0.5;
    /** The support hand reaches the grenade at the chest by this prime age. */
    public static final double PIN_REACH_SECONDS = 0.12;
    /** The support hand has pulled the pin by this prime age. */
    public static final double PIN_PULLED_SECONDS = 0.22;
    /** The throwing arm is fully cocked by this prime age. */
    public static final double COCKED_SECONDS = 0.5;
    /** The support hand has relaxed into its aiming stance by this prime age. */
    public static final double SUPPORT_RELAXED_SECONDS = 0.8;
    /** Empty-hand recovery (the last grenade left the hand) fades to vanilla arms over this tail of the throw. */
    public static final double EMPTY_FADE_SECONDS = 0.15;
    /** Strength ordinals carried as the throw variant ({@code GrenadeThrow.Strength}). */
    public static final int OVERHAND = 0, LOB = 1, MEDIUM = 2;
    private static final float MAX_AIM_PITCH = 1.2F;

    private static final int RX = 0, RY = 1, RZ = 2, LX = 3, LY = 4, LZ = 5, BODY = 6, AIM = 7;

    // rx, ry, rz, lx, ly, lz, body yaw, aim (fraction of head pitch added to both arms' X)
    private static final float[] READY = {-0.75F, -0.3F, 0.05F, -0.5F, 0.4F, -0.05F, 0F, 0.5F};
    private static final float[] PIN_REACH = {-0.95F, -0.55F, 0F, -1.05F, 0.75F, 0F, 0F, 0.4F};
    private static final float[] PIN_YANK = {-0.95F, -0.5F, 0F, -0.8F, 0.05F, -0.35F, 0F, 0.3F};
    private static final float[] COCKED = {-2.8F, 0.2F, 0.35F, -1.4F, 0.2F, -0.1F, 0.1F, 0.25F};
    private static final float[] COCKED_RELAXED = {-2.8F, 0.2F, 0.35F, -0.85F, 0.3F, -0.1F, 0.1F, 0.25F};

    private static final float[] PRIME_TIMES = {0F, (float) PIN_REACH_SECONDS, (float) PIN_PULLED_SECONDS,
            (float) COCKED_SECONDS, (float) SUPPORT_RELAXED_SECONDS};
    private static final float[][] PRIME_KEYS = {READY, PIN_REACH, PIN_YANK, COCKED, COCKED_RELAXED};

    // Throw keys are in seconds of the contract's 0.5 s throw and rescaled to the received duration.
    private static final float[] OVERHAND_TIMES = {0F, 0.08F, 0.16F, 0.5F};
    private static final float[][] OVERHAND_KEYS = {COCKED_RELAXED,
            {-1.9F, -0.1F, 0.1F, -0.4F, 0.1F, -0.15F, -0.12F, 0.2F},
            {-0.2F, -0.6F, -0.1F, -0.2F, 0F, -0.1F, -0.15F, 0F},
            READY};
    private static final float[] LOB_TIMES = {0F, 0.1F, 0.2F, 0.27F, 0.5F};
    private static final float[][] LOB_KEYS = {COCKED_RELAXED,
            {0.55F, 0.05F, 0.15F, -0.9F, 0.3F, -0.1F, 0.05F, 0F},
            {-1.45F, -0.15F, 0.05F, -0.5F, 0.2F, -0.1F, -0.05F, 0.2F},
            {-1.9F, -0.2F, 0.05F, -0.4F, 0.2F, -0.05F, -0.05F, 0.2F},
            READY};
    private static final float[] MEDIUM_TIMES = {0F, 0.06F, 0.15F, 0.5F};
    private static final float[][] MEDIUM_KEYS = {COCKED_RELAXED,
            {-1.6F, 0.75F, 1.0F, -0.9F, 0.3F, -0.1F, 0.08F, 0.2F},
            {-1.2F, -0.8F, 0.25F, -0.4F, 0.1F, -0.1F, -0.12F, 0.1F},
            READY};

    private GrenadePoseCurves() {}

    /**
     * Whether the grenade family owns this player's pose: any main-hand grenade, or a still-running throw after the
     * last grenade left the hand (empty main hand only, so a newly drawn gun or knife is never held in a grenade pose).
     */
    public static boolean claims(boolean holdingGrenade, boolean handEmpty, Stage stage, double ageSeconds,
            double durationSeconds) {
        if (holdingGrenade) return true;
        return handEmpty && stage == Stage.THROW && ageSeconds < throwDuration(durationSeconds);
    }

    /**
     * Writes the pose for {@code stage} at {@code ageSeconds} since it began. Primes hold their final cocked stance
     * indefinitely; throws are selected by strength ordinal ({@link #OVERHAND}, {@link #LOB}, {@link #MEDIUM}, anything
     * else overhand); {@link Stage#READY} or an expired throw is the ready grip. {@code headPitch} is the model head
     * X rotation (aim) in radians.
     */
    public static void pose(Stage stage, double ageSeconds, double durationSeconds, int variant, float headPitch,
            boolean holdingGrenade, Pose out) {
        float aimPitch = Math.max(-MAX_AIM_PITCH, Math.min(MAX_AIM_PITCH, headPitch));
        double duration = throwDuration(durationSeconds);
        float weight = 1F;
        if (stage == Stage.PRIME) {
            write(PRIME_TIMES, PRIME_KEYS, (float) Math.max(0.0, ageSeconds), aimPitch, out);
        } else if (stage == Stage.THROW && ageSeconds < duration) {
            float t = (float) (Math.max(0.0, ageSeconds) * THROW_SECONDS / duration);
            switch (variant) {
                case LOB -> write(LOB_TIMES, LOB_KEYS, t, aimPitch, out);
                case MEDIUM -> write(MEDIUM_TIMES, MEDIUM_KEYS, t, aimPitch, out);
                default -> write(OVERHAND_TIMES, OVERHAND_KEYS, t, aimPitch, out);
            }
            if (!holdingGrenade) {
                weight = 1F - smoothstep((float) ((ageSeconds - (duration - EMPTY_FADE_SECONDS)) / EMPTY_FADE_SECONDS));
            }
        } else {
            write(PRIME_TIMES, PRIME_KEYS, 0F, aimPitch, out); // the prime's first key is the ready grip
        }
        out.weight = weight;
        out.bodyYOffset *= weight;
    }

    private static double throwDuration(double durationSeconds) {
        return durationSeconds > 0.0 ? durationSeconds : THROW_SECONDS;
    }

    private static void write(float[] times, float[][] keys, float t, float aimPitch, Pose out) {
        float aim = sample(times, keys, AIM, t) * aimPitch;
        out.rightArmX = sample(times, keys, RX, t) + aim;
        out.rightArmY = sample(times, keys, RY, t);
        out.rightArmZ = sample(times, keys, RZ, t);
        out.leftArmX = sample(times, keys, LX, t) + aim;
        out.leftArmY = sample(times, keys, LY, t);
        out.leftArmZ = sample(times, keys, LZ, t);
        out.bodyYOffset = sample(times, keys, BODY, t);
    }

    /**
     * Cubic Hermite through the keys. Tangents are central differences, zero at the first and last key and at keys
     * where the channel reverses direction, so a swing never overshoots its extreme key.
     */
    private static float sample(float[] times, float[][] keys, int channel, float t) {
        int last = times.length - 1;
        if (t <= times[0]) return keys[0][channel];
        if (t >= times[last]) return keys[last][channel];
        int i = 0;
        while (t >= times[i + 1]) i++;
        float t0 = times[i], t1 = times[i + 1], h = t1 - t0;
        float p0 = keys[i][channel], p1 = keys[i + 1][channel];
        float m0 = tangent(times, keys, channel, i);
        float m1 = tangent(times, keys, channel, i + 1);
        float s = (t - t0) / h, s2 = s * s, s3 = s2 * s;
        return (2 * s3 - 3 * s2 + 1) * p0 + (s3 - 2 * s2 + s) * h * m0 + (-2 * s3 + 3 * s2) * p1
                + (s3 - s2) * h * m1;
    }

    private static float tangent(float[] times, float[][] keys, int channel, int k) {
        if (k == 0 || k == times.length - 1) return 0F;
        float before = keys[k][channel] - keys[k - 1][channel];
        float after = keys[k + 1][channel] - keys[k][channel];
        if (before * after <= 0F) return 0F;
        return (keys[k + 1][channel] - keys[k - 1][channel]) / (times[k + 1] - times[k - 1]);
    }

    private static float smoothstep(float x) {
        float c = Math.max(0F, Math.min(1F, x));
        return c * c * (3 - 2 * c);
    }
}
