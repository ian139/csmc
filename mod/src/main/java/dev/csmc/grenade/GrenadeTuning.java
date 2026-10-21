package dev.csmc.grenade;

/**
 * Authored grenade gameplay defaults. These are deliberately <b>not</b> claimed to be exact CS2 values:
 * the local {@code weapons.vdata} only supplies {@code m_flThrowVelocity = 750} for all six grenades and
 * HE {@code m_nDamage = 99}. Fuse, physics, radii and effect timings are absent there and are authored here.
 * {@code m_flRange} is not a blast radius and is not used; flash/smoke/decoy {@code m_nDamage} is ignored.
 */
public final class GrenadeTuning {
    private GrenadeTuning() {}

    /** vdata {@code m_flThrowVelocity}; read as CS units/second and scaled by {@link GrenadeThrow}. */
    public static final double THROW_VELOCITY_UNITS = 750.0;
    /** Ticks before any grenade item can be thrown again (all six share the cooldown). */
    public static final int THROW_COOLDOWN_TICKS = 15;

    // Physics (blocks, ticks).
    /** Projectile box edge (width and height), blocks. */
    public static final float PROJECTILE_SIZE = 0.25F;
    /**
     * 0.05 blocks/tick^2 = 20 blocks/s^2 = 320 units/s^2: CS {@code sv_gravity 800} times the grenade gravity
     * factor 0.4, so the CS launch speeds below give CS-like arcs without retuning.
     */
    public static final double GRAVITY = 0.05;
    public static final double AIR_DRAG = 0.99;
    public static final double WATER_DRAG = 0.6;
    public static final double WATER_GRAVITY = 0.015;
    /** Fraction of normal speed kept on a bounce. */
    public static final double RESTITUTION = 0.45;
    /** Fraction of tangential speed kept when bouncing off a floor. */
    public static final double FLOOR_BOUNCE_FRICTION = 0.8;
    /** Per-tick horizontal speed kept while rolling on the ground. */
    public static final double ROLL_FRICTION = 0.8;
    /** Downward speeds below this stop instead of bouncing. */
    public static final double MIN_BOUNCE_SPEED = 0.1;
    /** Ground speed below which a grenade comes to rest. */
    public static final double SETTLE_SPEED = 0.03;
    /** Fraction of speed kept after glancing off an entity (no damage is dealt). */
    public static final double ENTITY_BOUNCE = 0.3;

    // Fuses.
    /** HE and flashbang: 1.5 s from the throw. */
    public static final int TIMED_FUSE_TICKS = 30;
    /** Molotov/incendiary detonate on floor impact, else after 2 s (airburst without a floor below fizzles). */
    public static final int FIRE_FUSE_TICKS = 40;
    /** Fire airburst still lands a pool when a floor is this close below. */
    public static final int FIRE_AIRBURST_FLOOR_BLOCKS = 2;
    /** Smoke/decoy activate once at rest, or unconditionally after this guard (5 s). */
    public static final int REST_GUARD_TICKS = 100;

    // HE.
    /** vdata {@code m_nDamage} for {@code hegrenade}, in CS damage units, at the burst point. */
    public static final double HE_DAMAGE = 99.0;
    public static final double HE_RADIUS = 6.0;
    /** Peak knockback speed (blocks/tick) at the burst point, scaled by falloff and exposure. */
    public static final double HE_KNOCKBACK = 1.0;

    // Flashbang.
    public static final double FLASH_RADIUS = 20.0;
    /** Full-strength distance; severity falls linearly from here to {@link #FLASH_RADIUS}. */
    public static final double FLASH_FULL_RADIUS = 2.0;
    /** Severity multiplier when facing directly away (linear to 1.0 at 60 degrees off-axis). */
    public static final double FLASH_BEHIND_FACTOR = 0.25;
    public static final int FLASH_MAX_HOLD_TICKS = 40;
    public static final int FLASH_MAX_FADE_TICKS = 60;
    /** Severities below this send nothing. */
    public static final double FLASH_MIN_SEVERITY = 0.05;

    // Smoke.
    public static final float SMOKE_RADIUS = 4.0F;
    public static final float SMOKE_HEIGHT = 3.0F;
    public static final int SMOKE_GROW_TICKS = 20;
    public static final int SMOKE_DURATION_TICKS = 18 * 20;

    // Fire pool (molotov and incendiary share these; no verified per-item difference exists).
    public static final float FIRE_RADIUS = 3.0F;
    public static final float FIRE_HEIGHT = 1.0F;
    public static final int FIRE_DURATION_TICKS = 7 * 20;
    /** Upper bound on burning cells in one pool. */
    public static final int FIRE_MAX_CELLS = 64;
    public static final int FIRE_DAMAGE_INTERVAL_TICKS = 10;
    /** CS damage per pulse (2 pulses/s = 32 CS/s = 6.4 health/s). */
    public static final double FIRE_DAMAGE = 16.0;
    /** Vanilla burning visual/after-burn kept on a hurt entity. */
    public static final int FIRE_AFTERBURN_TICKS = 20;
    public static final int FIRE_BLOCK_INTERVAL_TICKS = 20;
    /** CS damage per interval to flammable blocks touching the pool (through BlockDamage). */
    public static final double FIRE_BLOCK_DAMAGE = 20.0;

    // Decoy.
    public static final int DECOY_DURATION_TICKS = 15 * 20;
    public static final int DECOY_MIN_GAP_TICKS = 4;
    public static final int DECOY_MAX_GAP_TICKS = 30;

    // Audio reach (blocks) for positional grenade events.
    public static final double SOUND_RANGE_LOUD = 96.0;
    public static final double SOUND_RANGE_QUIET = 32.0;
}
