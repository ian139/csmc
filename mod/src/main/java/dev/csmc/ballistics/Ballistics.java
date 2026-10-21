package dev.csmc.ballistics;

import dev.csmc.CsmcMod;
import dev.csmc.data.MaterialTable;
import dev.csmc.data.WeaponData;
import dev.csmc.network.GunRecoilPayload;
import dev.csmc.network.GunSoundPayload;
import dev.csmc.server.GunSounds;
import dev.csmc.server.GunSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server-authoritative Counter-Strike 2 style hitscan: stance + fire inaccuracy, ordered block/entity trace
 * with thickness-based penetration and block damage, CS damage model and deterministic recoil. Everything
 * runs on the logical server thread; clients only receive vanilla particles and sounds, crack overlays, a
 * {@link GunSoundPayload} fire event and a {@link GunRecoilPayload} view kick per shot.
 *
 * <p>Scale: 1 block = {@link WeaponData#UNITS_PER_BLOCK} (16) CS units, so weapon {@code range},
 * {@code penetration_distance}, wall thickness and range falloff are converted with that factor.
 */
public final class Ballistics {
    /** Damage type from {@code data/csmc/damage_type/bullet.json}; tagged bypasses_armor/bypasses_cooldown/no_knockback. */
    public static final ResourceKey<DamageType> BULLET_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, CsmcMod.id("bullet"));

    /**
     * ASSUMPTION: CS damage is in hit points out of a 100 HP player; Minecraft players have 20 health.
     * Bullets therefore deal {@code csDamage * 0.2} Minecraft health (an AK body shot of 36 CS = 7.2
     * health = 3.6 hearts; an unarmored AK headshot of 144 CS = 28.8 health, lethal like in CS). The same
     * scale is used for every entity, so high-health mobs take proportionally longer to kill.
     */
    public static final double CS_TO_MC_HEALTH = 20.0 / 100.0;

    static final double UNITS_PER_BLOCK = WeaponData.UNITS_PER_BLOCK;
    /** CS range falloff: {@code damage *= range_modifier ^ (distanceUnits / 500)}. */
    static final double FALLOFF_UNITS = 500.0;

    /*
     * Penetration (CS:GO HandleBulletPenetration shape, CS2 surface data; constants are an approximation):
     * an obstacle is a run of solid blocks the ray crosses without an air gap. Its thickness t (CS units) is
     * the ray length inside the blocks' actual collision boxes (entry to exit per box, so hollow shapes only
     * count their solid parts and overhanging shapes are met in the cell they overhang into); distance/damage
     * modifiers are the thickness-weighted means of the blocks' surface modifiers. Passing it costs
     *   damage * (1 - damageModifier) * DAMAGE_CHUNK_SCALE         (surface damage chunk)
     * + (3 / penetration_power * 1.25) / distanceModifier          (weapon power)
     * + t^2 / THICKNESS_DIVISOR / distanceModifier                 (squared thickness)
     * The bullet stops in the block where that loss would leave less than MIN_DAMAGE, when t exceeds
     * penetration_distance, or when penetration_count obstacles have already been passed.
     */
    /** Scales the surface damage chunk so CS2 Wood (damage modifier 0.6) loses CS:GO's flat 16%. */
    static final double DAMAGE_CHUNK_SCALE = 0.4;
    /** CS:GO weapon power term {@code 3 / power * 1.25}. */
    static final double POWER_TERM = 3.0 * 1.25;
    /** CS:GO squared-thickness divisor. */
    static final double THICKNESS_DIVISOR = 24.0;
    /** CS:GO: bullets with less than 1 damage left stop. */
    static final double MIN_DAMAGE = 1.0;
    /** Exit of one block and entry of the next closer than this (blocks) belong to the same obstacle. */
    static final double CONTIGUOUS_EPSILON = 1.0E-4;
    /** Entities are penetrated as flesh; approximate surface modifiers (no Minecraft equivalent in CS data). */
    static final double FLESH_DISTANCE_MODIFIER = 1.0;
    static final double FLESH_DAMAGE_MODIFIER = 0.9;

    /** CS run speed (units/s) that weapon {@code max_speed} is relative to. */
    static final double CS_RUN_SPEED = 250.0;
    /** Minecraft walking speed in blocks per tick; maps to {@link #CS_RUN_SPEED}. */
    static final double MC_WALK_BLOCKS_PER_TICK = 4.317 / 20.0;
    /** CS movement inaccuracy ramps in from 34% to 95% of the weapon's max speed. */
    static final double MOVE_INACCURACY_START = 0.34;
    static final double MOVE_INACCURACY_FULL = 0.95;
    /** Spray (recoil index) resets after one weapon cycle plus this many quiet ticks. */
    static final int SHOT_RESET_GRACE_TICKS = 4;
    /** Entity candidates are gathered lazily in ray segments of this length (blocks). */
    private static final double ENTITY_SEGMENT_BLOCKS = 16.0;
    /**
     * Ray intervals inside a collision box shorter than this (blocks) are grazes of a face or edge and
     * do not count as a hit.
     */
    private static final double MIN_INTERVAL_BLOCKS = 1.0E-7;
    /** An obstacle whose exit lies this close to the range end still contains the bullet's end point. */
    private static final double RANGE_END_EPSILON = 0.01;

    private Ballistics() {}

    /** Registers the {@code csmcBlockBreaking} gamerule and the server tick/disconnect hooks. Call once from mod init. */
    public static void register() {
        BlockBreakPolicy.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ShooterState.tick(server);
            BlockDamageTracker.tick(server);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ShooterState.remove(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ShooterState.reset();
            BlockDamageTracker.reset();
        });
    }

    /**
     * Fires one shot (all {@code pellets}) for a player whose cadence/ammo was already accepted by the
     * caller. Computes server-side inaccuracy from stance and accumulated fire penalty, traces every pellet
     * from the reported view plus the recoil the client has not echoed yet, plays the shot effects, adds the
     * fire inaccuracy and sends the shot's recoil kick. Returns that kick. Use {@link #trace} for diagnostics.
     * {@code sequence} and {@code magazineAfter} (the shooter's shot number and post-shot magazine) travel with the
     * FIRE event so clients can match it to the synced magazine and pick last-round viewmodel clips.
     */
    public static WeaponData.Kick fire(ServerPlayer player, WeaponData weapon, int sequence, int magazineAfter) {
        ServerLevel level = player.serverLevel();
        ShooterState state = ShooterState.of(player);
        state.bind(weapon, player);
        boolean scoped = GunSystem.scopeLevel(player) > 0;
        long now = level.getGameTime();
        long quietTicks = (long) Math.ceil(weapon.activeCycleTimeS(scoped) * 20.0) + SHOT_RESET_GRACE_TICKS;
        int shot = state.nextShot(now, quietTicks);

        double inaccuracy = state.accuracyPenalty + stanceInaccuracy(player, state, weapon, scoped);
        Vec3 eye = player.getEyePosition();
        Vec3 aim = aimDirection(player.getXRot(), player.getYRot(), state.pendingPitch(), state.pendingYaw());
        RandomSource random = player.getRandom();

        for (int i = 0; i < weapon.pellets(); i++) {
            TraceResult result = trace(level, player, eye,
                    applySpread(aim, inaccuracy, weapon.activeSpreadBase(scoped), random), weapon);
            tracer(level, eye, result.end());
        }

        GunSounds.broadcast(player, weapon.id(), GunSoundPayload.FIRE, eye, 0, sequence, magazineAfter, 0);
        Vec3 muzzle = eye.add(aim.scale(0.8));
        level.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 2, 0.02, 0.02, 0.02, 0.01);

        state.accuracyPenalty += weapon.activeInaccuracy(scoped).fire();
        WeaponData.Kick kick = weapon.recoil().kick(shot);
        kick(player, state, kick, now);
        return kick;
    }

    /**
     * Bullet aim: the reported view ({@code xRot} negative looking up) raised by {@code pendingPitch} and
     * turned right by {@code pendingYaw} degrees, pitch clamped to straight up/down like the client view.
     */
    static Vec3 aimDirection(float xRot, float yRot, double pendingPitch, double pendingYaw) {
        float pitch = Mth.clamp((float) (xRot - pendingPitch), -90.0F, 90.0F);
        return Vec3.directionFromRotation(pitch, (float) (yRot + pendingYaw));
    }

    /**
     * Traces one bullet without spread or recoil. Blocks and entities are processed strictly in ray order;
     * entities behind the block that stops the bullet are never hit. {@code shooter} may be null
     * (diagnostics/GameTest): block protection then only checks hardness, gamerule and world border,
     * and damage uses the bullet damage type without an attacker.
     */
    public static TraceResult trace(
            ServerLevel level, @Nullable ServerPlayer shooter, Vec3 start, Vec3 direction, WeaponData weapon) {
        return new Tracer(level, shooter, start, direction.normalize(), weapon).run();
    }

    /**
     * CS2 stance inaccuracy: crouch or stand base, plus ladder (climbing, off the ground), jump (airborne)
     * or movement scaled by horizontal speed relative to the weapon's max speed. Landing adds
     * {@code inaccuracy.land} to the decaying penalty ({@link ShooterState}).
     */
    static double stanceInaccuracy(ServerPlayer player, ShooterState state, WeaponData weapon, boolean scoped) {
        WeaponData.Inaccuracy in = weapon.activeInaccuracy(scoped);
        double total = player.isCrouching() ? in.crouch() : in.stand();
        boolean grounded = player.onGround() || player.isPassenger();
        if (!grounded && player.onClimbable()) {
            total += in.ladder();
        } else if (!grounded) {
            total += in.jump();
        } else {
            total += in.move() * movementScale(state.horizontalSpeed * dev.csmc.movement.CsMovement.inaccuracySpeedScale(player), weapon.activeMaxSpeed(scoped));
        }
        return total;
    }

    /** 0 below 34% of the weapon's max speed, 1 from 95%; {@code max_speed} 250 maps to Minecraft walking. */
    static double movementScale(double blocksPerTick, double maxSpeed) {
        double max = maxSpeed / CS_RUN_SPEED * MC_WALK_BLOCKS_PER_TICK;
        double from = MOVE_INACCURACY_START * max;
        double to = MOVE_INACCURACY_FULL * max;
        return Mth.clamp((blocksPerTick - from) / (to - from), 0.0, 1.0);
    }

    /**
     * CS2 bullet direction: {@code aim + x * right + y * up}, where (x, y) is the sum of a random point in a
     * disc of radius {@code inaccuracy} and one in a disc of radius {@code spread} (radius uniform in [0, r]).
     */
    static Vec3 applySpread(Vec3 aim, double inaccuracy, double spread, RandomSource random) {
        if (inaccuracy <= 0.0 && spread <= 0.0) {
            return aim;
        }
        Vec3 right = aim.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0E-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        }
        right = right.normalize();
        Vec3 up = right.cross(aim).normalize();
        double theta0 = random.nextDouble() * Mth.TWO_PI;
        double radius0 = random.nextDouble() * inaccuracy;
        double theta1 = random.nextDouble() * Mth.TWO_PI;
        double radius1 = random.nextDouble() * spread;
        double x = Math.cos(theta0) * radius0 + Math.cos(theta1) * radius1;
        double y = Math.sin(theta0) * radius0 + Math.sin(theta1) * radius1;
        return aim.add(right.scale(x)).add(up.scale(y)).normalize();
    }

    /**
     * Sends one deterministic recoil kick ({@link WeaponData.Recoil#kick}) to the client, which adds it to its
     * real view; Minecraft has no separate punch angle, so the view is never pulled back and the player
     * compensates manually. A custom payload rather than a relative teleport: teleports make the server
     * ignore movement until the client acknowledges them, which rubber-bands automatic fire. Until the
     * client has had time to apply the kick (round-trip latency plus one tick) the server adds it to the
     * aim of further shots itself, so recoil stays server-authoritative for hit registration.
     */
    static void kick(ServerPlayer player, ShooterState state, WeaponData.Kick kick, long now) {
        if (kick.pitch() == 0.0 && kick.yaw() == 0.0) {
            return;
        }
        ServerPlayNetworking.send(player, new GunRecoilPayload((float) kick.pitch(), (float) kick.yaw()));
        state.addPendingKick(kick, now, absorbDelayTicks(player.connection.latency()));
    }

    /** Ticks until a kick sent now shows up in the client's rotation reports: one round trip plus one tick. */
    static long absorbDelayTicks(int latencyMs) {
        return (long) Math.ceil(Math.max(0, latencyMs) / 50.0) + 1L;
    }

    private static void tracer(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 2.0) {
            return;
        }
        int steps = (int) Math.min(32, length / 2.0);
        for (int i = 1; i <= steps; i++) {
            Vec3 p = from.add(delta.scale(i / (double) (steps + 1)));
            level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    static DamageSource bulletSource(ServerLevel level, @Nullable ServerPlayer shooter) {
        return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(BULLET_DAMAGE)
                .map(type -> new DamageSource(type, shooter, shooter))
                .orElseGet(() -> shooter != null
                        ? level.damageSources().playerAttack(shooter)
                        : level.damageSources().generic());
    }

    /**
     * Damage lost passing an obstacle of {@code thicknessUnits} with the given mean surface modifiers;
     * infinite when the bullet cannot penetrate at all (no power or zero distance modifier).
     */
    static double penetrationLoss(
            WeaponData weapon, double damage, double thicknessUnits, double distanceModifier, double damageModifier) {
        if (!(weapon.penetrationPower() > 0.0) || !(distanceModifier > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        double inverse = 1.0 / distanceModifier;
        double chunk = damage * Mth.clamp(1.0 - damageModifier, 0.0, 1.0) * DAMAGE_CHUNK_SCALE;
        double power = POWER_TERM / weapon.penetrationPower() * inverse;
        double thickness = thicknessUnits * thicknessUnits / THICKNESS_DIVISOR * inverse;
        return chunk + power + thickness;
    }

    /** CS hit group from where the ray entered the target's box (heuristic for humanoid proportions). */
    static TraceResult.HitGroup hitGroup(Entity entity, Vec3 location) {
        if (!(entity instanceof LivingEntity living)) {
            return TraceResult.HitGroup.GENERIC;
        }
        AABB box = living.getBoundingBox();
        double height = box.getYsize();
        if (height <= 0.0) {
            return TraceResult.HitGroup.GENERIC;
        }
        double relative = (location.y - box.minY) / height;
        double headStart = living.getEyeHeight() / height - 0.12;
        if (relative >= headStart) {
            return TraceResult.HitGroup.HEAD;
        }
        if (relative >= 0.55) {
            return TraceResult.HitGroup.CHEST;
        }
        if (relative >= 0.40) {
            return TraceResult.HitGroup.STOMACH;
        }
        return TraceResult.HitGroup.LEGS;
    }

    /**
     * CS hit-group scaling followed by {@link ArmorModel}'s tiered, slot-specific armor absorption.
     * The bullet type bypasses vanilla armor (not enchantments) to avoid a second defense reduction,
     * and bypasses the hurt cooldown so automatic fire and pellets all register.
     *
     * <p>No target state is mutated unless the hit is allowed: player targets are first checked with
     * {@link MinecraftServer#isPvpAllowed()} and {@link Player#canHarmPlayer} (teams/friendly fire), and
     * armor is only worn after vanilla {@link Entity#hurt} accepted the damage (invulnerability, shields,
     * spawn protection etc. reject it there).
     */
    static TraceResult.EntityImpact hitEntity(
            ServerLevel level, @Nullable ServerPlayer shooter, DamageSource source, WeaponData weapon, Entity entity,
            double csDamage, double distance, Vec3 location) {
        TraceResult.HitGroup group = hitGroup(entity, location);
        double damage = csDamage * group.multiplier(weapon);
        if (shooter != null && entity instanceof Player target
                && (!level.getServer().isPvpAllowed() || !shooter.canHarmPlayer(target))) {
            return new TraceResult.EntityImpact(entity, group, damage, 0.0F, false, distance, location);
        }
        ItemStack armor = ItemStack.EMPTY;
        EquipmentSlot armorSlot = null;
        int armorWear = 0;
        if (entity instanceof LivingEntity living) {
            ArmorModel.Absorption absorbed = ArmorModel.absorb(
                    living, group, damage, weapon.armorRatio(), weapon.armorBonus());
            damage = absorbed.healthDamage();
            armorSlot = absorbed.slot();
            armorWear = absorbed.armorWear();
            armor = armorSlot == null ? ItemStack.EMPTY : living.getItemBySlot(armorSlot);
        }
        float healthDamage = (float) (damage * CS_TO_MC_HEALTH);
        if (healthDamage <= 0.0F) {
            return new TraceResult.EntityImpact(entity, group, damage, healthDamage, false, distance, location);
        }
        if (entity instanceof LivingEntity living && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
            living.invulnerableTime = 0; // fallback source only: keep automatic fire from being swallowed
        }
        boolean hurt = entity.hurt(source, healthDamage);
        if (hurt) {
            if (entity instanceof LivingEntity living && armorSlot != null && armorWear >= 1
                    && armor.isDamageableItem()
                    && living.getLastDamageSource() == source) {
                // Vanilla records accepted damage even if a totem restores health before hurt() returns.
                // An Enderman projectile dodge can return true without recording damage.
                // hurt() can drop/clear equipment on death. Wear the captured absorbing stack, never a replacement.
                armor.hurtAndBreak(armorWear, living, armorSlot);
            }
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, location.x, location.y, location.z, 2, 0.1, 0.1, 0.1, 0.1);
        }
        return new TraceResult.EntityImpact(entity, group, damage, healthDamage, hurt, distance, location);
    }

    /** One bullet's walk along the ray. Not reusable. */
    private static final class Tracer {
        private record Candidate(Entity entity, double distance, double exit, Vec3 location) {}

        /**
         * One block's collision shape inside one visited cell; distances in blocks along the ray. {@code removed}:
         * the block was destroyed by this bullet and {@code state} is its original state.
         */
        private record Segment(
                BlockPos pos, BlockState state, double entry, double exit, double thicknessBlocks, boolean removed) {}

        private final ServerLevel level;
        @Nullable
        private final ServerPlayer shooter;
        private final Vec3 start;
        private final Vec3 direction;
        private final Vec3 end;
        private final WeaponData weapon;
        private final double maxRange;
        private final CollisionContext collision;
        private final DamageSource source;
        private final List<TraceResult.Impact> impacts = new ArrayList<>();
        private final PriorityQueue<Candidate> candidates =
                new PriorityQueue<>((a, b) -> Double.compare(a.distance(), b.distance()));
        private final IntSet seenEntities = new IntOpenHashSet();

        private double fetchedUpTo;
        private double damage;
        /** Obstacles the bullet may still pass completely. */
        private int passesLeft;
        private double lastDistance;

        /** Current obstacle: contiguous solid blocks. Sums are thickness-weighted modifiers. */
        private boolean inRun;
        private double runExit;
        private double runThicknessUnits;
        private double runDistanceSum;
        private double runDamageSum;
        private double runLastDistanceModifier;
        private double runLastDamageModifier;
        /** Index in {@link #impacts} and position of the current obstacle's last block. */
        private int runImpact;
        @Nullable
        private BlockPos runPos;
        /**
         * Original state of {@link #runPos} if this bullet destroyed it: the shape still counts (e.g. a wall's
         * overhang into the cell above) until the ray leaves it, without hitting the block again.
         */
        @Nullable
        private BlockState runRemovedState;
        /**
         * Assembly counterparts (piston base, other door half...) this bullet's breaks removed, by position, with
         * their original states: the ray still crosses their material once, without damaging them again.
         */
        @Nullable
        private Long2ObjectMap<BlockState> removedCounterparts;

        private TraceResult.StopReason stopReason;
        private BlockPos stoppedAt;
        private Vec3 stopLocation;

        Tracer(ServerLevel level, @Nullable ServerPlayer shooter, Vec3 start, Vec3 direction, WeaponData weapon) {
            this.level = level;
            this.shooter = shooter;
            this.start = start;
            this.direction = direction;
            this.weapon = weapon;
            this.maxRange = weapon.rangeBlocks();
            this.end = start.add(direction.scale(maxRange));
            this.collision = shooter != null ? CollisionContext.of(shooter) : CollisionContext.empty();
            this.source = bulletSource(level, shooter);
            this.damage = weapon.damage();
            this.passesLeft = weapon.penetrationCount();
        }

        TraceResult run() {
            BlockGetter.traverseBlocks(start, end, this, Tracer::visit, tracer -> null);
            if (stopReason == null) {
                if (inRun && runExit >= maxRange - RANGE_END_EPSILON) {
                    // Range ends inside the obstacle: the bullet never exits, so it neither pays the exit
                    // loss nor uses up a penetration.
                    inRun = false;
                    if (entitiesBefore(maxRange)) {
                        falloffTo(maxRange);
                        stop(TraceResult.StopReason.RANGE, null, at(maxRange));
                    }
                } else if (advanceTo(maxRange)) {
                    falloffTo(maxRange);
                    stop(TraceResult.StopReason.RANGE, null, at(maxRange));
                }
            }
            return new TraceResult(start, direction, stopLocation, stopReason, stoppedAt, damage, passesLeft, impacts);
        }

        /**
         * traverseBlocks callback: non-null stops the walk. Handles the collision shapes inside this cell —
         * the cell's own block and the part of the block below that overhangs into it (fences, walls and
         * fence gates are 1.5 blocks tall) — in ray order, so every interval lies inside the cell's own ray
         * segment and ordering against entities and other cells holds.
         */
        @Nullable
        private Boolean visit(BlockPos cell) {
            if (!level.isOutsideBuildHeight(cell) && !level.isLoaded(cell)) {
                double entry = Math.min(new AABB(cell).clip(start, end).map(start::distanceTo).orElse(lastDistance), maxRange);
                if (advanceTo(entry)) {
                    stop(TraceResult.StopReason.UNLOADED, null, at(entry));
                }
                return Boolean.TRUE;
            }
            Segment own = segment(cell.immutable(), cell, false);
            Segment overhang = segment(cell.below(), cell, true);
            if (overhang != null && (own == null || overhang.entry() < own.entry())) {
                Segment first = overhang;
                overhang = own;
                own = first;
            }
            if (own != null && !process(own)) {
                return Boolean.TRUE;
            }
            return overhang != null && !process(overhang) ? Boolean.TRUE : null;
        }

        /** Hits or continues the block of one segment. Returns false if the bullet stopped. */
        private boolean process(Segment segment) {
            if (inRun && segment.pos().equals(runPos) && segment.entry() <= runExit + CONTIGUOUS_EPSILON) {
                return continueBlock(segment);
            }
            // Resolve counterparts now, not when the segment was built: a cell's second segment (e.g. a piston
            // head's arm reaching into its base's cell) is built before the first one's break removes it.
            BlockState counterpart = removedCounterparts == null ? null : removedCounterparts.remove(segment.pos().asLong());
            if (counterpart == null && segment.removed()) {
                // The ray left the destroyed block's shape and re-enters it: it is gone now.
                return true;
            }
            if (!advanceTo(segment.entry())) {
                return false;
            }
            falloffTo(segment.entry());
            return block(segment.pos(), counterpart != null ? counterpart : segment.state(), segment.entry(),
                    segment.exit(), segment.thicknessBlocks() * UNITS_PER_BLOCK, at(segment.entry()), counterpart != null);
        }

        /**
         * The ray's intervals inside the collision shape of the block at {@code pos}, restricted to the unit
         * {@code cell}: entry of the first, exit of the last interval and the summed length, so hollow shapes
         * (cauldrons, hoppers, scaffolding) only count their solid parts. Null if the ray misses the shape.
         * With {@code overhangOnly}, shapes not reaching above their own cell are skipped cheaply.
         */
        @Nullable
        private Segment segment(BlockPos pos, BlockPos cell, boolean overhangOnly) {
            if (level.isOutsideBuildHeight(pos)) {
                return null;
            }
            boolean continuing = inRun && runRemovedState != null && pos.equals(runPos);
            BlockState counterpart = continuing || removedCounterparts == null ? null : removedCounterparts.get(pos.asLong());
            boolean removed = continuing || counterpart != null;
            BlockState state = continuing ? runRemovedState : counterpart != null ? counterpart : level.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            VoxelShape shape = state.getCollisionShape(level, pos, collision);
            if (shape.isEmpty() || overhangOnly && shape.max(Direction.Axis.Y) <= 1.0) {
                return null;
            }
            double ox = cell.getX() - pos.getX();
            double oy = cell.getY() - pos.getY();
            double oz = cell.getZ() - pos.getZ();
            List<double[]> intervals = new ArrayList<>(2);
            shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
                double[] t = rayInterval(
                        pos.getX() + Math.max(x0, ox), pos.getY() + Math.max(y0, oy), pos.getZ() + Math.max(z0, oz),
                        pos.getX() + Math.min(x1, ox + 1.0), pos.getY() + Math.min(y1, oy + 1.0),
                        pos.getZ() + Math.min(z1, oz + 1.0));
                if (t != null) {
                    intervals.add(t);
                }
            });
            if (intervals.isEmpty()) {
                return null;
            }
            intervals.sort((a, b) -> Double.compare(a[0], b[0]));
            double entry = intervals.get(0)[0];
            double from = entry;
            double to = intervals.get(0)[1];
            double length = 0.0;
            for (int i = 1; i < intervals.size(); i++) {
                double[] next = intervals.get(i);
                if (next[0] > to + CONTIGUOUS_EPSILON) {
                    length += to - from;
                    from = next[0];
                }
                to = Math.max(to, next[1]);
            }
            length += to - from;
            return new Segment(pos, state, entry, to, length, removed);
        }

        /**
         * Distances {@code [entry, exit]} (blocks, within {@code [0, maxRange]}) where the ray is inside the
         * box, or null if it misses it or only grazes a face or edge.
         */
        @Nullable
        private double[] rayInterval(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
                return null;
            }
            double t0 = 0.0;
            double t1 = maxRange;
            for (int axis = 0; axis < 3; axis++) {
                double s = axis == 0 ? start.x : axis == 1 ? start.y : start.z;
                double d = axis == 0 ? direction.x : axis == 1 ? direction.y : direction.z;
                double lo = axis == 0 ? minX : axis == 1 ? minY : minZ;
                double hi = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
                if (Math.abs(d) < 1.0E-12) {
                    if (s <= lo || s >= hi) {
                        return null;
                    }
                    continue;
                }
                double a = (lo - s) / d;
                double b = (hi - s) / d;
                t0 = Math.max(t0, Math.min(a, b));
                t1 = Math.min(t1, Math.max(a, b));
                if (t1 - t0 <= MIN_INTERVAL_BLOCKS) {
                    return null;
                }
            }
            return new double[] {t0, t1};
        }

        /**
         * Finishes the current obstacle if {@code distance} lies beyond it (falloff to its exit, then the
         * penetration loss), then hits every entity entered before {@code distance}. Returns false if the
         * bullet stopped.
         */
        private boolean advanceTo(double distance) {
            if (inRun && distance > runExit + CONTIGUOUS_EPSILON) {
                falloffTo(runExit);
                damage -= runLoss(damage);
                passesLeft--;
                inRun = false;
            }
            return entitiesBefore(distance);
        }

        /** Loss for passing the current obstacle with {@code exitDamage} left when leaving it. */
        private double runLoss(double exitDamage) {
            double distanceModifier = runThicknessUnits > 0.0 ? runDistanceSum / runThicknessUnits : runLastDistanceModifier;
            double damageModifier = runThicknessUnits > 0.0 ? runDamageSum / runThicknessUnits : runLastDamageModifier;
            return penetrationLoss(weapon, exitDamage, runThicknessUnits, distanceModifier, damageModifier);
        }

        /** Damage after range falloff from the last processed distance to {@code distance}. */
        private double damageAt(double distance) {
            return distance > lastDistance
                    ? damage * Math.pow(weapon.rangeModifier(), (distance - lastDistance) * UNITS_PER_BLOCK / FALLOFF_UNITS)
                    : damage;
        }

        /**
         * Applies the material rules to a block the bullet reached at {@code distance}; {@code thicknessUnits}
         * is the ray length inside the block's collision shape up to {@code exit}. {@code alreadyRemoved}: the
         * block is an assembly counterpart this bullet already removed; it costs its original material like any
         * block but is neither permission-checked nor damaged again. Returns true if the bullet continues.
         */
        private boolean block(BlockPos pos, BlockState state, double distance, double exit, double thicknessUnits,
                Vec3 location, boolean alreadyRemoved) {
            MaterialTable.Material material = MaterialTable.lookup(state);
            double hitDamage = damage;
            boolean newObstacle = !inRun;

            if (material.stops() || state.getDestroySpeed(level, pos) < 0.0F) {
                return blockStop(pos, state, material, hitDamage, thicknessUnits, distance, location,
                        TraceResult.StopReason.BLOCK);
            }
            if (newObstacle && passesLeft <= 0) {
                return blockStop(pos, state, material, hitDamage, thicknessUnits, distance, location,
                        TraceResult.StopReason.PENETRATION);
            }

            TraceResult.BlockOutcome outcome = TraceResult.BlockOutcome.PASSED;
            if (alreadyRemoved) {
                outcome = TraceResult.BlockOutcome.BROKEN;
            } else {
                // Entering any solid block needs permission (gamerule, protection), even without changing it,
                // so a denied shooter can neither damage protected cover nor shoot through it.
                if (!BlockBreakPolicy.mayModify(level, shooter, pos, state)) {
                    return blockStop(pos, state, material, hitDamage, thicknessUnits, distance, location,
                            TraceResult.StopReason.FORBIDDEN);
                }
                if (material.breakMode() != MaterialTable.BreakMode.NONE) {
                    BlockDamage.Result result = BlockDamage.applyPermitted(
                            level, shooter, pos, state, material, hitDamage, this::rememberCounterpart);
                    switch (result.outcome()) {
                        case DENIED -> {
                            // Includes a destruction another mod cancelled: the block is still there and occludes.
                            return blockStop(pos, state, material, hitDamage, thicknessUnits, distance, location,
                                    TraceResult.StopReason.FORBIDDEN);
                        }
                        case BROKEN -> outcome = material.breakMode() == MaterialTable.BreakMode.SHATTER
                                ? TraceResult.BlockOutcome.SHATTERED
                                : TraceResult.BlockOutcome.BROKEN;
                        case INTACT -> {
                            if (result.appliedDamage() > 0.0) {
                                outcome = TraceResult.BlockOutcome.DAMAGED;
                            }
                        }
                    }
                }
            }

            // The bullet crosses the block's material even if it just broke it, including any part of its
            // original shape in later cells.
            if (newObstacle) {
                inRun = true;
                runThicknessUnits = 0.0;
                runDistanceSum = 0.0;
                runDamageSum = 0.0;
            }
            impacts.add(new TraceResult.BlockImpact(pos, state, material.name(), outcome, hitDamage, thicknessUnits,
                    distance, location));
            runImpact = impacts.size() - 1;
            runPos = pos;
            boolean modified = outcome == TraceResult.BlockOutcome.SHATTERED || outcome == TraceResult.BlockOutcome.BROKEN;
            runRemovedState = modified ? state : null;
            boolean continues = addThickness(material, thicknessUnits, exit);
            if (!modified && (newObstacle || !continues)) {
                impactEffects(pos, state, location, !continues);
            }
            return continues;
        }

        private void rememberCounterpart(BlockPos pos, BlockState state) {
            if (removedCounterparts == null) {
                removedCounterparts = new Long2ObjectOpenHashMap<>(2);
            }
            removedCounterparts.put(pos.asLong(), state);
        }

        /**
         * The ray left a cell still inside the current obstacle's last block (its shape overhangs into the
         * next cell): adds the extra material to that block's impact and the obstacle without a second hit.
         */
        private boolean continueBlock(Segment segment) {
            double thicknessUnits = segment.thicknessBlocks() * UNITS_PER_BLOCK;
            TraceResult.BlockImpact last = (TraceResult.BlockImpact) impacts.get(runImpact);
            impacts.set(runImpact, new TraceResult.BlockImpact(last.pos(), last.state(), last.material(), last.outcome(),
                    last.csDamage(), last.thicknessUnits() + thicknessUnits, last.distance(), last.location()));
            boolean continues = addThickness(MaterialTable.lookup(segment.state()), thicknessUnits, segment.exit());
            if (!continues) {
                impactEffects(last.pos(), last.state(), last.location(), true);
            }
            return continues;
        }

        /**
         * Adds material crossed inside the current obstacle's last block, then applies the penetration
         * limits. Returns false (and marks the block's impact as stopping, unless it was modified) if the
         * bullet stops inside it.
         */
        private boolean addThickness(MaterialTable.Material material, double thicknessUnits, double exit) {
            runThicknessUnits += thicknessUnits;
            runDistanceSum += thicknessUnits * material.distanceModifier();
            runDamageSum += thicknessUnits * material.damageModifier();
            runLastDistanceModifier = material.distanceModifier();
            runLastDamageModifier = material.damageModifier();
            runExit = exit;

            TraceResult.StopReason stopBy = null;
            double exitDamage = damageAt(exit);
            if (runThicknessUnits > weapon.penetrationDistance()) {
                stopBy = TraceResult.StopReason.PENETRATION;
            } else if (exit < maxRange - RANGE_END_EPSILON && exitDamage - runLoss(exitDamage) < MIN_DAMAGE) {
                // Same falloff-then-loss computation advanceTo applies when the obstacle is finished.
                stopBy = TraceResult.StopReason.POWER;
            }
            if (stopBy == null) {
                return true;
            }
            TraceResult.BlockImpact last = (TraceResult.BlockImpact) impacts.get(runImpact);
            if (last.outcome() == TraceResult.BlockOutcome.PASSED) {
                impacts.set(runImpact, new TraceResult.BlockImpact(last.pos(), last.state(), last.material(),
                        TraceResult.BlockOutcome.STOPPED, last.csDamage(), last.thicknessUnits(), last.distance(),
                        last.location()));
            }
            inRun = false;
            stop(stopBy, last.pos(), last.location());
            return false;
        }

        private boolean blockStop(BlockPos pos, BlockState state, MaterialTable.Material material, double hitDamage,
                double thicknessUnits, double distance, Vec3 location, TraceResult.StopReason reason) {
            impacts.add(new TraceResult.BlockImpact(pos, state, material.name(), TraceResult.BlockOutcome.STOPPED,
                    hitDamage, thicknessUnits, distance, location));
            impactEffects(pos, state, location, true);
            inRun = false;
            stop(reason, pos, location);
            return false;
        }

        private void impactEffects(BlockPos pos, BlockState state, Vec3 location, boolean stopped) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    location.x, location.y, location.z, 6, 0.05, 0.05, 0.05, 0.1);
            if (stopped) {
                level.sendParticles(ParticleTypes.SMOKE, location.x, location.y, location.z, 2, 0.02, 0.02, 0.02, 0.01);
            }
            level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.6F, 1.2F);
        }

        /**
         * Hits every entity entered before {@code distance}, in order. Each entity is an obstacle of flesh:
         * it takes the current damage, then the bullet passes only with penetrations left and enough damage.
         * Returns false if the bullet stopped.
         */
        private boolean entitiesBefore(double distance) {
            gatherEntities(distance);
            while (!candidates.isEmpty() && candidates.peek().distance() <= distance) {
                Candidate candidate = candidates.poll();
                if (!candidate.entity().isAlive()) {
                    continue;
                }
                falloffTo(candidate.distance());
                impacts.add(hitEntity(level, shooter, source, weapon, candidate.entity(), damage, candidate.distance(),
                        candidate.location()));
                if (passesLeft <= 0) {
                    stop(TraceResult.StopReason.PENETRATION, null, candidate.location());
                    return false;
                }
                double thicknessUnits = (candidate.exit() - candidate.distance()) * UNITS_PER_BLOCK;
                double loss = penetrationLoss(weapon, damage, thicknessUnits, FLESH_DISTANCE_MODIFIER, FLESH_DAMAGE_MODIFIER);
                if (damage - loss < MIN_DAMAGE) {
                    stop(TraceResult.StopReason.POWER, null, candidate.location());
                    return false;
                }
                damage -= loss;
                passesLeft--;
            }
            return true;
        }

        /** Lazily collects entities whose (pick-radius inflated) box the ray enters, segment by segment. */
        private void gatherEntities(double upTo) {
            double target = Math.min(upTo, maxRange);
            // "<=" also fetches the first segment when the first event is at distance 0.
            while (fetchedUpTo <= target && fetchedUpTo < maxRange) {
                double from = fetchedUpTo;
                double to = Math.min(maxRange, from + ENTITY_SEGMENT_BLOCKS);
                AABB segment = new AABB(at(from), at(to)).inflate(1.0);
                for (Entity entity : level.getEntities(shooter, segment, this::targetable)) {
                    if (!seenEntities.add(entity.getId())) {
                        continue;
                    }
                    AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
                    double exit = box.clip(end, start).map(start::distanceTo).orElse(0.0);
                    if (box.contains(start)) {
                        candidates.add(new Candidate(entity, 0.0, exit, start));
                    } else {
                        box.clip(start, end).ifPresent(hit -> {
                            double entry = start.distanceTo(hit);
                            candidates.add(new Candidate(entity, entry, Math.max(entry, exit), hit));
                        });
                    }
                }
                fetchedUpTo = to;
                if (to >= maxRange) {
                    break;
                }
            }
        }

        private boolean targetable(Entity entity) {
            if (entity.isSpectator() || !entity.isAlive() || !entity.isPickable()) {
                return false;
            }
            return shooter == null || entity.getRootVehicle() != shooter.getRootVehicle();
        }

        private void falloffTo(double distance) {
            if (distance > lastDistance) {
                damage = damageAt(distance);
                lastDistance = distance;
            }
        }

        private Vec3 at(double distance) {
            return start.add(direction.scale(distance));
        }

        private void stop(TraceResult.StopReason reason, @Nullable BlockPos pos, Vec3 location) {
            stopReason = reason;
            stoppedAt = pos;
            stopLocation = location;
        }
    }
}
