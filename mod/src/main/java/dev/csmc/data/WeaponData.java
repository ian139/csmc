package dev.csmc.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable, validated Counter-Strike 2 weapon definition loaded from
 * {@code data/<namespace>/weapons/<path>.json}. Field meanings follow CS2 {@code weapons.vdata}
 * ({@code CCSWeaponBaseVData}). Bundled files hold self-authored approximations; at load time the importer
 * overlays fields from the user's own local export (never written back to resources).
 *
 * <p>Units:
 * <ul>
 *   <li>{@code damage}: CS hit points per bullet (per pellet for shotguns) before range falloff,
 *       hit group and armor ({@code m_nDamage}). Conversion to Minecraft health is the ballistics layer's job.</li>
 *   <li>{@code cycleTimeS}: seconds between shots ({@code m_flCycleTime}); {@link #rateOfFireRpm()} is derived.</li>
 *   <li>{@code range}: maximum trace distance in CS units (1 block = {@link #UNITS_PER_BLOCK} units).</li>
 *   <li>{@code rangeModifier}: falloff base, {@code damage *= rangeModifier ^ (distanceUnits / 500)}.</li>
 *   <li>{@code penetrationPower}: {@code m_flPenetration} (pistols/SMGs 1, rifles 2, snipers 2.5); 0 never penetrates.</li>
 *   <li>{@code penetrationCount}: obstacles (contiguous solid runs or entities) a bullet may pass completely.</li>
 *   <li>{@code penetrationDistance}: thickest single obstacle in CS units a bullet can exit (CS exit-trace limit).</li>
 *   <li>{@code armorRatio}: raw CS2 {@code m_flArmorRatio} (AK-47 1.55); armored hits deal
 *       {@code damage * armorRatio * 0.5} (clamped to the full damage) to health.</li>
 *   <li>{@code armorBonus}: armor lost = {@code (damage - health part) * armorBonus}.</li>
 *   <li>{@code headshotMultiplier}: {@code m_flHeadshotMultiplier}.</li>
 *   <li>{@code maxSpeed}: {@code m_flMaxSpeed} in CS units per second (250 = unarmed run speed).</li>
 *   <li>{@code spreadBase}, {@link Inaccuracy}: unitless tangent offsets of the aim vector ({@code m_flSpread},
 *       {@code m_flInaccuracy*}); {@link Recovery}: seconds ({@code m_flRecoveryTime*}).</li>
 *   <li>{@link Recoil}: CS2 recoil parameters plus an optional measured per-shot pattern.</li>
 *   <li>{@link Zoom}: scope levels as CS horizontal field of view in degrees ({@code m_nZoomFOV*}), transition
 *       times in seconds ({@code m_flZoomTime*}); {@link Scoped}: the scoped firing mode (second element of
 *       CS2 {@code CFiringModeFloat} arrays), selected by the {@code active*} accessors.</li>
 * </ul>
 */
public record WeaponData(
        ResourceLocation id,
        String displayName,
        double damage,
        double cycleTimeS,
        int magazineSize,
        int reserveAmmo,
        double reloadTimeS,
        double deployTimeS,
        double range,
        double rangeModifier,
        double penetrationPower,
        int penetrationCount,
        double penetrationDistance,
        double armorRatio,
        double armorBonus,
        double headshotMultiplier,
        int pellets,
        double maxSpeed,
        double spreadBase,
        Inaccuracy inaccuracy,
        Recovery recovery,
        Recoil recoil,
        boolean automatic,
        String ammoType,
        Zoom zoom,
        Scoped scoped) {

    /** CS units per Minecraft block; the mod-wide scale. */
    public static final double UNITS_PER_BLOCK = 16.0;

    public static final int DEFAULT_RESERVE_AMMO = 0;
    public static final double DEFAULT_RELOAD_TIME_S = 2.5;
    /** CS2 {@code weapon_base} {@code m_flDeployDuration}. */
    public static final double DEFAULT_DEPLOY_TIME_S = 1.0;
    public static final double DEFAULT_RANGE = 8192.0;
    public static final double DEFAULT_RANGE_MODIFIER = 0.98;
    public static final double DEFAULT_PENETRATION_POWER = 1.0;
    /** CS:GO/CS2 bullets pass at most four obstacles. */
    public static final int DEFAULT_PENETRATION_COUNT = 4;
    /** CS exit-trace search limit: no exit within 90 units means the obstacle is too thick. */
    public static final double DEFAULT_PENETRATION_DISTANCE = 90.0;
    public static final double DEFAULT_ARMOR_RATIO = 1.0;
    public static final double DEFAULT_ARMOR_BONUS = 0.5;
    public static final double DEFAULT_HEADSHOT_MULTIPLIER = 4.0;
    public static final int DEFAULT_PELLETS = 1;
    /** CS unarmed run speed, units per second. */
    public static final double DEFAULT_MAX_SPEED = 250.0;
    /**
     * Highest accepted weapon/held movement speed in units/s (8× the unarmed 250); shared with the movement-state
     * protocol. It also bounds the per-tick collision-collection radius of CS movement.
     */
    public static final double MAX_ALLOWED_SPEED = 2_000.0;
    public static final double DEFAULT_SPREAD_BASE = 0.0;
    public static final boolean DEFAULT_AUTOMATIC = false;

    /** CS2 {@code m_flInaccuracy*} terms; all unitless tangent offsets, default 0. */
    public record Inaccuracy(
            double stand, double crouch, double move, double jump, double land, double ladder, double fire) {
        public static final Inaccuracy NONE = new Inaccuracy(0, 0, 0, 0, 0, 0, 0);

        public Inaccuracy {
            requireNonNegative("inaccuracy.stand", stand);
            requireNonNegative("inaccuracy.crouch", crouch);
            requireNonNegative("inaccuracy.move", move);
            requireNonNegative("inaccuracy.jump", jump);
            requireNonNegative("inaccuracy.land", land);
            requireNonNegative("inaccuracy.ladder", ladder);
            requireNonNegative("inaccuracy.fire", fire);
        }
    }

    /**
     * CS2 scope: {@code levels} holds the horizontal FOV in degrees of each zoom level ({@code m_nZoomFOV1},
     * {@code m_nZoomFOV2}, ...; empty = the weapon has no scope). {@code timesS} index {@code n} is the
     * transition time into level {@code n} ({@code m_flZoomTime0} for leaving the scope, {@code 1}/{@code 2}
     * for the zoom levels); missing indices use {@code timeS}. {@code unzoomAfterShot} is
     * {@code m_bUnzoomsAfterShot}.
     */
    public record Zoom(List<Double> levels, double timeS, List<Double> timesS, boolean unzoomAfterShot) {
        /** Transition time used when {@code time_s} is absent (the observed CS2 AWP/SSG 08 value). */
        public static final double DEFAULT_TIME_S = 0.05;
        public static final Zoom NONE = new Zoom(List.of(), DEFAULT_TIME_S, List.of(), false);

        public Zoom {
            levels = List.copyOf(Objects.requireNonNull(levels, "levels"));
            timesS = List.copyOf(Objects.requireNonNull(timesS, "timesS"));
            for (int i = 0; i < levels.size(); i++) {
                double fov = levels.get(i);
                if (!(fov > 0.0 && fov < 180.0)) {
                    throw new IllegalArgumentException("zoom.levels[" + i + "] must be in (0, 180) degrees, got " + fov);
                }
            }
            requireNonNegative("zoom.time_s", timeS);
            for (int i = 0; i < timesS.size(); i++) {
                requireNonNegative("zoom.times_s[" + i + "]", timesS.get(i));
            }
        }

        /** True when the weapon has at least one zoom level. */
        public boolean enabled() {
            return !levels.isEmpty();
        }

        public int levelCount() {
            return levels.size();
        }

        /** Horizontal FOV in CS degrees of the 1-based zoom {@code level} (1..{@link #levelCount()}). */
        public double fovDegrees(int level) {
            if (level < 1 || level > levels.size()) {
                throw new IndexOutOfBoundsException("zoom level " + level + " outside 1.." + levels.size());
            }
            return levels.get(level - 1);
        }

        /** Seconds to transition into {@code level} (0 = unscoped); falls back to {@link #timeS()}. */
        public double transitionTimeS(int level) {
            return level >= 0 && level < timesS.size() ? timesS.get(level) : timeS;
        }
    }

    /**
     * Scoped firing mode: second element of CS2's {@code m_flSpread}, {@code m_flInaccuracy*},
     * {@code m_flMaxSpeed} and {@code m_flCycleTime} (a scalar export applies to both modes). Same units as
     * the unscoped fields; members missing from JSON inherit the unscoped value.
     */
    public record Scoped(double spreadBase, Inaccuracy inaccuracy, double maxSpeed, double cycleTimeS) {
        public Scoped {
            Objects.requireNonNull(inaccuracy, "scoped.inaccuracy");
            requireNonNegative("scoped.spread_base", spreadBase);
            requireMaxSpeed("scoped.max_speed", maxSpeed);
            requirePositive("scoped.cycle_time_s", cycleTimeS);
        }
    }

    /**
     * CS2 {@code m_flRecoveryTime*}: seconds for the accumulated fire/land inaccuracy to decay to 10%.
     * The time blends from {@code stand}/{@code crouch} to {@code standFinal}/{@code crouchFinal} as the
     * shot index goes from {@code startBullet} to {@code endBullet} (CS2 recovery transition bullets).
     */
    public record Recovery(
            double stand, double crouch, double standFinal, double crouchFinal, int startBullet, int endBullet) {
        /** Used when the {@code recovery} object or its {@code stand} member is missing. */
        public static final double DEFAULT_STAND_S = 0.35;
        public static final Recovery DEFAULT =
                new Recovery(DEFAULT_STAND_S, DEFAULT_STAND_S, DEFAULT_STAND_S, DEFAULT_STAND_S, 0, 0);

        public Recovery {
            requireNonNegative("recovery.stand", stand);
            requireNonNegative("recovery.crouch", crouch);
            requireNonNegative("recovery.stand_final", standFinal);
            requireNonNegative("recovery.crouch_final", crouchFinal);
            if (startBullet < 0 || endBullet < 0) {
                throw new IllegalArgumentException("recovery start_bullet/end_bullet must be >= 0, got "
                        + startBullet + "/" + endBullet);
            }
            if (endBullet < startBullet) {
                throw new IllegalArgumentException("recovery end_bullet must be >= start_bullet, got "
                        + startBullet + "/" + endBullet);
            }
        }

        /** Recovery time in seconds for the given stance and 1-based shot index. */
        public double timeS(boolean crouching, int shotIndex) {
            double base = crouching ? crouch : stand;
            double fin = crouching ? crouchFinal : standFinal;
            double t;
            if (endBullet > startBullet) {
                t = Math.min(1.0, Math.max(0.0, (shotIndex - startBullet) / (double) (endBullet - startBullet)));
            } else {
                t = shotIndex >= startBullet && startBullet > 0 ? 1.0 : 0.0;
            }
            return base + (fin - base) * t;
        }
    }

    /** One shot's view kick in degrees: positive pitch raises the view, positive yaw turns it right. */
    public record Kick(double pitch, double yaw) {
        public static final Kick NONE = new Kick(0.0, 0.0);

        public Kick {
            if (!Double.isFinite(pitch) || !Double.isFinite(yaw)) {
                throw new IllegalArgumentException("recoil kick must be finite, got " + pitch + ", " + yaw);
            }
        }
    }

    /**
     * CS2 recoil: {@code m_flRecoilAngle} / {@code …Variance} (degrees, 0 = straight up, positive toward
     * the right), {@code m_flRecoilMagnitude} / {@code …Variance} and {@code m_nRecoilSeed}.
     *
     * <p>{@link #kick(int)} is deterministic for a given weapon and shot index:
     * <ul>
     *   <li>With a {@code pattern} (measured from the real game, one kick per shot), shot {@code n} uses
     *       entry {@code n - 1}; shots past the end repeat the last entry.</li>
     *   <li>Otherwise an explicit APPROXIMATION is used, because CS2's recoil RNG and magnitude units are
     *       not public: two uniforms in [-1, 1) come from SplitMix64 of {@code (seed, shot)}, giving
     *       {@code angle + u1 * angleVariance} and {@code max(0, magnitude + u2 * magnitudeVariance)}; the kick is
     *       {@code magnitude * }{@link #DEGREES_PER_MAGNITUDE} degrees along that direction. The resulting spray is
     *       the same every time for a weapon but does not reproduce CS2's exact pattern.</li>
     * </ul>
     */
    public record Recoil(
            double angle, double angleVariance, double magnitude, double magnitudeVariance, long seed, List<Kick> pattern) {
        /** Approximate view degrees per CS2 recoil magnitude unit (AK-47 magnitude 30 ≈ 0.75° per shot). */
        public static final double DEGREES_PER_MAGNITUDE = 0.025;
        public static final Recoil NONE = new Recoil(0.0, 0.0, 0.0, 0.0, 0L, List.of());

        public Recoil {
            requireFinite("recoil.angle", angle);
            requireNonNegative("recoil.angle_variance", angleVariance);
            requireNonNegative("recoil.magnitude", magnitude);
            requireNonNegative("recoil.magnitude_variance", magnitudeVariance);
            pattern = List.copyOf(Objects.requireNonNull(pattern, "pattern"));
        }

        /** View kick for the 1-based shot index of the current spray. */
        public Kick kick(int shotIndex) {
            int shot = Math.max(1, shotIndex);
            if (!pattern.isEmpty()) {
                return pattern.get(Math.min(shot, pattern.size()) - 1);
            }
            if (magnitude <= 0.0 && magnitudeVariance <= 0.0) {
                return Kick.NONE;
            }
            long state = seed * 0x9E3779B97F4A7C15L + shot;
            long a = splitMix64(state);
            long b = splitMix64(a);
            double directionDeg = angle + signedUnit(a) * angleVariance;
            double size = Math.max(0.0, magnitude + signedUnit(b) * magnitudeVariance) * DEGREES_PER_MAGNITUDE;
            double radians = Math.toRadians(directionDeg);
            return new Kick(size * Math.cos(radians), size * Math.sin(radians));
        }

        private static long splitMix64(long x) {
            long z = x + 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /** Top 53 bits mapped to [-1, 1). */
        private static double signedUnit(long bits) {
            return (bits >>> 11) * 0x1.0p-52 - 1.0;
        }
    }

    public WeaponData {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(ammoType, "ammoType");
        Objects.requireNonNull(inaccuracy, "inaccuracy");
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(recoil, "recoil");
        Objects.requireNonNull(zoom, "zoom");
        Objects.requireNonNull(scoped, "scoped");
        requirePositive("damage", damage);
        requirePositive("cycle_time_s", cycleTimeS);
        if (magazineSize < 1) {
            throw new IllegalArgumentException("magazine_size must be >= 1, got " + magazineSize);
        }
        if (reserveAmmo < 0) {
            throw new IllegalArgumentException("reserve_ammo must be >= 0, got " + reserveAmmo);
        }
        requireNonNegative("reload_time_s", reloadTimeS);
        requireNonNegative("deploy_time_s", deployTimeS);
        requirePositive("range", range);
        if (!(rangeModifier > 0.0 && rangeModifier <= 1.0)) {
            throw new IllegalArgumentException("range_modifier must be in (0, 1], got " + rangeModifier);
        }
        requireNonNegative("penetration_power", penetrationPower);
        if (penetrationCount < 0) {
            throw new IllegalArgumentException("penetration_count must be >= 0, got " + penetrationCount);
        }
        requireNonNegative("penetration_distance", penetrationDistance);
        requireNonNegative("armor_ratio", armorRatio);
        requireNonNegative("armor_bonus", armorBonus);
        requirePositive("headshot_multiplier", headshotMultiplier);
        if (pellets < 1) {
            throw new IllegalArgumentException("pellets must be >= 1, got " + pellets);
        }
        requireMaxSpeed("max_speed", maxSpeed);
        requireNonNegative("spread_base", spreadBase);
        if (ammoType.isBlank()) {
            throw new IllegalArgumentException("ammo_type must not be blank");
        }
    }

    /** Rounds per minute, {@code 60 / cycleTimeS}. */
    public double rateOfFireRpm() {
        return 60.0 / cycleTimeS;
    }

    private boolean useScoped(boolean scopedMode) {
        return scopedMode && zoom.enabled();
    }

    /** Spread base of the firing mode: {@link #scoped()} when {@code scopedMode} and the weapon can zoom. */
    public double activeSpreadBase(boolean scopedMode) {
        return useScoped(scopedMode) ? scoped.spreadBase() : spreadBase;
    }

    /** Inaccuracy terms of the firing mode: {@link #scoped()} when {@code scopedMode} and the weapon can zoom. */
    public Inaccuracy activeInaccuracy(boolean scopedMode) {
        return useScoped(scopedMode) ? scoped.inaccuracy() : inaccuracy;
    }

    /** Max speed (CS units/s) of the firing mode: {@link #scoped()} when {@code scopedMode} and the weapon can zoom. */
    public double activeMaxSpeed(boolean scopedMode) {
        return useScoped(scopedMode) ? scoped.maxSpeed() : maxSpeed;
    }

    /** Seconds between shots of the firing mode: {@link #scoped()} when {@code scopedMode} and the weapon can zoom. */
    public double activeCycleTimeS(boolean scopedMode) {
        return useScoped(scopedMode) ? scoped.cycleTimeS() : cycleTimeS;
    }

    /** Whole server ticks between shots (at 20 TPS), at least 1. */
    public int cycleTicks() {
        return Math.max(1, (int) Math.round(cycleTimeS * 20.0));
    }

    /** Reload duration in whole server ticks (at 20 TPS). */
    public int reloadTicks() {
        return (int) Math.round(reloadTimeS * 20.0);
    }

    /** Deploy (draw) duration in whole server ticks (at 20 TPS). */
    public int deployTicks() {
        return (int) Math.round(deployTimeS * 20.0);
    }

    /** Maximum trace distance in blocks. */
    public double rangeBlocks() {
        return range / UNITS_PER_BLOCK;
    }

    /**
     * Parses a weapon JSON object. {@code fileId} is derived from the file path; an explicit
     * {@code "id"} member, when present, must match it.
     *
     * <p>Required: {@code damage}, {@code magazine_size}, {@code cycle_time_s}. Optional:
     * {@code display_name} / {@code ammo_type} (→ id path), {@code reserve_ammo}, {@code reload_time_s},
     * {@code deploy_time_s}, {@code range}, {@code range_modifier}, {@code penetration_power},
     * {@code penetration_count}, {@code penetration_distance}, {@code armor_ratio}, {@code armor_bonus},
     * {@code headshot_multiplier}, {@code pellets}, {@code max_speed}, {@code spread_base}, {@code automatic}
     * (→ {@code DEFAULT_*}); {@code inaccuracy} {@code {stand, crouch, move, jump, land, ladder, fire}}
     * (missing members 0); {@code recovery} {@code {stand, crouch, stand_final, crouch_final, start_bullet,
     * end_bullet}} (stand → {@link Recovery#DEFAULT_STAND_S}, crouch → stand, finals → their base, bullets → 0);
     * {@code recoil} {@code {angle, angle_variance, magnitude, magnitude_variance, seed, pattern}} (missing
     * members 0, pattern optional array of {@code [pitch_deg, yaw_deg]}; missing recoil = none);
     * {@code zoom} {@code {levels, time_s, times_s, unzoom_after_shot}} (levels: array of horizontal FOV degrees,
     * missing/empty = no scope; time_s → {@link Zoom#DEFAULT_TIME_S}; times_s optional array, index 0 = unzoom;
     * unzoom_after_shot → false; missing zoom = {@link Zoom#NONE}); {@code scoped}
     * {@code {spread_base, inaccuracy, max_speed, cycle_time_s}} where every missing member, including each
     * missing {@code inaccuracy} member, inherits the unscoped value.
     * Explicit null equals missing. Unknown members are ignored. Wrong types and out-of-range values are errors.
     *
     * @throws JsonParseException with a field-specific message for malformed input
     */
    public static WeaponData fromJson(ResourceLocation fileId, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("weapon definition must be a JSON object");
        }
        JsonObject json = element.getAsJsonObject();

        String declaredId = optString(json, "id", null);
        if (declaredId != null) {
            ResourceLocation parsed = ResourceLocation.tryParse(declaredId);
            if (parsed == null) {
                throw new JsonParseException("id '" + declaredId + "' is not a valid resource location");
            }
            if (!parsed.equals(fileId)) {
                throw new JsonParseException("id '" + parsed + "' does not match file id '" + fileId + "'");
            }
        }

        try {
            double cycleTimeS = reqDouble(json, "cycle_time_s");
            double maxSpeed = optDouble(json, "max_speed", DEFAULT_MAX_SPEED);
            double spreadBase = optDouble(json, "spread_base", DEFAULT_SPREAD_BASE);
            Inaccuracy inaccuracy = parseInaccuracy(optObject(json, "inaccuracy"), Inaccuracy.NONE);
            return new WeaponData(
                    fileId,
                    optString(json, "display_name", fileId.getPath()),
                    reqDouble(json, "damage"),
                    cycleTimeS,
                    reqInt(json, "magazine_size"),
                    optInt(json, "reserve_ammo", DEFAULT_RESERVE_AMMO),
                    optDouble(json, "reload_time_s", DEFAULT_RELOAD_TIME_S),
                    optDouble(json, "deploy_time_s", DEFAULT_DEPLOY_TIME_S),
                    optDouble(json, "range", DEFAULT_RANGE),
                    optDouble(json, "range_modifier", DEFAULT_RANGE_MODIFIER),
                    optDouble(json, "penetration_power", DEFAULT_PENETRATION_POWER),
                    optInt(json, "penetration_count", DEFAULT_PENETRATION_COUNT),
                    optDouble(json, "penetration_distance", DEFAULT_PENETRATION_DISTANCE),
                    optDouble(json, "armor_ratio", DEFAULT_ARMOR_RATIO),
                    optDouble(json, "armor_bonus", DEFAULT_ARMOR_BONUS),
                    optDouble(json, "headshot_multiplier", DEFAULT_HEADSHOT_MULTIPLIER),
                    optInt(json, "pellets", DEFAULT_PELLETS),
                    maxSpeed,
                    spreadBase,
                    inaccuracy,
                    parseRecovery(optObject(json, "recovery")),
                    parseRecoil(optObject(json, "recoil")),
                    optBoolean(json, "automatic", DEFAULT_AUTOMATIC),
                    optString(json, "ammo_type", fileId.getPath()),
                    parseZoom(optObject(json, "zoom")),
                    parseScoped(optObject(json, "scoped"), spreadBase, inaccuracy, maxSpeed, cycleTimeS));
        } catch (IllegalArgumentException e) {
            throw new JsonParseException(e.getMessage(), e);
        }
    }

    private static Zoom parseZoom(JsonObject o) {
        if (o == null) {
            return Zoom.NONE;
        }
        return new Zoom(
                numbers(o, "levels", "zoom.levels"),
                optDouble(o, "time_s", Zoom.DEFAULT_TIME_S),
                numbers(o, "times_s", "zoom.times_s"),
                optBoolean(o, "unzoom_after_shot", false));
    }

    private static Scoped parseScoped(
            JsonObject o, double spreadBase, Inaccuracy inaccuracy, double maxSpeed, double cycleTimeS) {
        if (o == null) {
            return new Scoped(spreadBase, inaccuracy, maxSpeed, cycleTimeS);
        }
        return new Scoped(
                optDouble(o, "spread_base", spreadBase),
                parseInaccuracy(optObject(o, "inaccuracy"), inaccuracy),
                optDouble(o, "max_speed", maxSpeed),
                optDouble(o, "cycle_time_s", cycleTimeS));
    }

    /** Optional array of numbers; missing or null = empty. */
    private static List<Double> numbers(JsonObject o, String key, String label) {
        if (!present(o, key)) {
            return List.of();
        }
        if (!(o.get(key) instanceof JsonArray array)) {
            throw new JsonParseException(label + " must be an array of numbers, got " + o.get(key));
        }
        List<Double> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            values.add(number(array.get(i), label + "[" + i + "]"));
        }
        return values;
    }

    /** Missing members use {@code fallback}'s value (0 for the unscoped {@link Inaccuracy#NONE}). */
    private static Inaccuracy parseInaccuracy(JsonObject o, Inaccuracy fallback) {
        if (o == null) {
            return fallback;
        }
        return new Inaccuracy(
                optDouble(o, "stand", fallback.stand()),
                optDouble(o, "crouch", fallback.crouch()),
                optDouble(o, "move", fallback.move()),
                optDouble(o, "jump", fallback.jump()),
                optDouble(o, "land", fallback.land()),
                optDouble(o, "ladder", fallback.ladder()),
                optDouble(o, "fire", fallback.fire()));
    }

    private static Recovery parseRecovery(JsonObject o) {
        if (o == null) {
            return Recovery.DEFAULT;
        }
        double stand = optDouble(o, "stand", Recovery.DEFAULT_STAND_S);
        double crouch = optDouble(o, "crouch", stand);
        return new Recovery(
                stand,
                crouch,
                optDouble(o, "stand_final", stand),
                optDouble(o, "crouch_final", crouch),
                optInt(o, "start_bullet", 0),
                optInt(o, "end_bullet", 0));
    }

    private static Recoil parseRecoil(JsonObject o) {
        if (o == null) {
            return Recoil.NONE;
        }
        return new Recoil(
                optDouble(o, "angle", 0.0),
                optDouble(o, "angle_variance", 0.0),
                optDouble(o, "magnitude", 0.0),
                optDouble(o, "magnitude_variance", 0.0),
                optLong(o, "seed", 0L),
                parsePattern(o.get("pattern")));
    }

    /** {@code [[pitch_deg, yaw_deg], ...]}, one entry per shot. */
    private static List<Kick> parsePattern(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException("recoil.pattern must be an array of [pitch_deg, yaw_deg] pairs");
        }
        JsonArray shots = element.getAsJsonArray();
        List<Kick> kicks = new ArrayList<>(shots.size());
        for (int i = 0; i < shots.size(); i++) {
            String key = "recoil.pattern[" + i + "]";
            if (!(shots.get(i) instanceof JsonArray pair) || pair.size() != 2) {
                throw new JsonParseException(key + " must be a [pitch_deg, yaw_deg] pair, got " + shots.get(i));
            }
            kicks.add(new Kick(number(pair.get(0), key + "[0]"), number(pair.get(1), key + "[1]")));
        }
        return kicks;
    }

    // --- JSON helpers (also used by MaterialTable): missing or null optional members use the default; wrong types are errors. ---

    private static boolean present(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && !e.isJsonNull();
    }

    private static JsonObject optObject(JsonObject json, String key) {
        if (!present(json, key)) {
            return null;
        }
        if (json.get(key) instanceof JsonObject o) {
            return o;
        }
        throw new JsonParseException(key + " must be an object, got " + json.get(key));
    }

    private static double number(JsonElement e, String key) {
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            double value = p.getAsDouble();
            if (!Double.isFinite(value)) {
                throw new JsonParseException(key + " must be finite, got " + value);
            }
            return value;
        }
        throw new JsonParseException(key + " must be a number, got " + e);
    }

    static double reqDouble(JsonObject json, String key) {
        if (!present(json, key)) {
            throw new JsonParseException("missing required field '" + key + "'");
        }
        return number(json.get(key), key);
    }

    static double optDouble(JsonObject json, String key, double fallback) {
        return present(json, key) ? number(json.get(key), key) : fallback;
    }

    private static int toInt(double value, String key) {
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JsonParseException(key + " must be an integer, got " + value);
        }
        return (int) value;
    }

    private static int reqInt(JsonObject json, String key) {
        return toInt(reqDouble(json, key), key);
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return present(json, key) ? toInt(number(json.get(key), key), key) : fallback;
    }

    private static long optLong(JsonObject json, String key, long fallback) {
        if (!present(json, key)) {
            return fallback;
        }
        if (json.get(key) instanceof JsonPrimitive p && p.isNumber()) {
            try {
                return p.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException e) {
                throw new JsonParseException(key + " must be an integer, got " + p);
            }
        }
        throw new JsonParseException(key + " must be an integer, got " + json.get(key));
    }

    static boolean optBoolean(JsonObject json, String key, boolean fallback) {
        if (!present(json, key)) {
            return fallback;
        }
        if (json.get(key) instanceof JsonPrimitive p && p.isBoolean()) {
            return p.getAsBoolean();
        }
        throw new JsonParseException(key + " must be a boolean, got " + json.get(key));
    }

    static String optString(JsonObject json, String key, String fallback) {
        if (!present(json, key)) {
            return fallback;
        }
        if (json.get(key) instanceof JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        throw new JsonParseException(key + " must be a string, got " + json.get(key));
    }

    private static void requireFinite(String key, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(key + " must be finite, got " + value);
        }
    }

    private static void requirePositive(String key, double value) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(key + " must be a positive finite number, got " + value);
        }
    }

    private static void requireMaxSpeed(String key, double value) {
        requirePositive(key, value);
        if (value > MAX_ALLOWED_SPEED) {
            throw new IllegalArgumentException(key + " must be at most " + MAX_ALLOWED_SPEED + ", got " + value);
        }
    }

    private static void requireNonNegative(String key, double value) {
        if (!(value >= 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(key + " must be a non-negative finite number, got " + value);
        }
    }
}
