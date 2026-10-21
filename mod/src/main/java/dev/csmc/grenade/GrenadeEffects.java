package dev.csmc.grenade;

import dev.csmc.ballistics.Ballistics;
import dev.csmc.ballistics.BlockDamage;
import dev.csmc.network.GrenadeFlashPayload;
import dev.csmc.network.GrenadeSoundPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side grenade effects: owner resolution and harm guards, HE blast (entities and terrain), flashbang
 * severity and positional audio. Smoke, fire and decoy behavior live in their entities.
 *
 * <p>Ownership is fail-closed: an effect that would modify terrain or hurt anything needs a resolved,
 * connected {@link ServerPlayer} owner in the same level. A grenade whose owner logged out, changed dimension
 * or is unknown still makes its sound/particles but neither damages entities nor touches blocks; it never
 * degrades to the nullable diagnostic path of {@link BlockDamage}.
 */
public final class GrenadeEffects {
    private GrenadeEffects() {}

    /** One player's flash result, as sent in a {@link GrenadeFlashPayload}. */
    public record FlashHit(ServerPlayer player, double severity, GrenadeFlashPayload payload) {}

    /** One entity's HE result: CS damage before exposure, exposure in [0, 1], health actually removed. */
    public record BlastHit(Entity entity, double csDamage, double exposure, float healthLost) {}

    /** The connected owning player in {@code level}, or null (which disables every harmful effect). */
    public static @Nullable ServerPlayer resolveOwner(ServerLevel level, @Nullable UUID owner) {
        if (owner == null) {
            return null;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player == null || player.isRemoved() || player.hasDisconnected() || player.serverLevel() != level) {
            return null;
        }
        return player;
    }

    /**
     * Whether {@code owner}'s grenade may hurt {@code target}: other players need server PvP and
     * {@link Player#canHarmPlayer} (scoreboard team friendly-fire); spectators and creative players are
     * left to vanilla {@link Entity#hurt}. Self-damage is allowed, as in CS.
     */
    public static boolean mayHarm(ServerPlayer owner, Entity target) {
        if (target.isSpectator()) {
            return false;
        }
        if (target instanceof Player player && player != owner) {
            return owner.server.isPvpAllowed() && owner.canHarmPlayer(player);
        }
        return true;
    }

    /** Positional event to every player in {@code level} within {@code range} blocks of {@code pos}. */
    public static void sound(ServerLevel level, GrenadeKind kind, String event, Vec3 pos, float volume, float pitch,
            double range) {
        GrenadeSoundPayload payload = new GrenadeSoundPayload(kind.id(), event, pos.x, pos.y, pos.z, volume, pitch);
        double rangeSq = range * range;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= rangeSq && ServerPlayNetworking.canSend(player, GrenadeSoundPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    // ---------------------------------------------------------------- HE

    /** HE CS damage at {@code distance} blocks before exposure: linear falloff to 0 at {@link GrenadeTuning#HE_RADIUS}. */
    public static double heDamageAt(double distance) {
        return GrenadeTuning.HE_DAMAGE * Mth.clamp(1.0 - distance / GrenadeTuning.HE_RADIUS, 0.0, 1.0);
    }

    /**
     * HE detonation at {@code center}. Entities: CS damage {@link #heDamageAt} using the distance to the nearest
     * point of their box, times vanilla ray exposure ({@link Explosion#getSeenPercent}, so solid cover blocks it),
     * converted with {@link Ballistics#CS_TO_MC_HEALTH}, dealt as vanilla explosion damage (armor and Blast
     * Protection apply) attributed to the owner, plus knockback. Terrain: {@link #blastBlocks}. Requires a
     * resolved owner; without one only the sound and particles happen.
     */
    public static List<BlastHit> explodeHe(ServerLevel level, Entity grenade, @Nullable ServerPlayer owner, Vec3 center) {
        sound(level, GrenadeKind.HE, GrenadeSoundPayload.EVENT_DETONATE, center, 1.0F, 1.0F, GrenadeTuning.SOUND_RANGE_LOUD);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0.0, 0.0, 0.0, 0.0);
        List<BlastHit> hits = new ArrayList<>();
        if (owner == null) {
            return hits;
        }
        double radius = GrenadeTuning.HE_RADIUS;
        DamageSource source = level.damageSources().explosion(grenade, owner);
        // Self hits are already authorized by mayHarm; not player-attributed so vanilla's PvP/team veto cannot cancel them.
        DamageSource selfSource = level.damageSources().explosion(grenade, null);
        List<Entity> targets = level.getEntities(grenade, new AABB(center, center).inflate(radius),
                entity -> entity.isAlive() && !entity.isSpectator() && !(entity instanceof GrenadeProjectile)
                        && !(entity instanceof GrenadeCloud));
        for (Entity target : targets) {
            double distance = distanceToBox(center, target.getBoundingBox());
            double csDamage = heDamageAt(distance);
            if (csDamage <= 0.0 || !mayHarm(owner, target)) {
                continue;
            }
            double exposure = Explosion.getSeenPercent(center, target);
            if (exposure <= 0.0) {
                continue;
            }
            float before = target instanceof LivingEntity living ? living.getHealth() : 0.0F;
            float health = (float) (csDamage * exposure * Ballistics.CS_TO_MC_HEALTH);
            boolean hurt = target.hurt(target == owner ? selfSource : source, health);
            float lost = target instanceof LivingEntity living ? before - living.getHealth() : 0.0F;
            if (hurt) {
                knockback(target, center, csDamage / GrenadeTuning.HE_DAMAGE * exposure);
            }
            hits.add(new BlastHit(target, csDamage, exposure, lost));
        }
        blastBlocks(level, owner, center);
        return hits;
    }

    private static void knockback(Entity target, Vec3 center, double strength) {
        Vec3 away = target.getBoundingBox().getCenter().subtract(center);
        if (away.lengthSqr() < 1.0E-6) {
            away = new Vec3(0.0, 1.0, 0.0);
        }
        double resist = target instanceof LivingEntity living
                ? living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE) : 0.0;
        double speed = GrenadeTuning.HE_KNOCKBACK * strength * (1.0 - Mth.clamp(resist, 0.0, 1.0));
        if (speed > 0.0) {
            target.setDeltaMovement(target.getDeltaMovement().add(away.normalize().scale(speed)));
            target.hurtMarked = true;
        }
    }

    static double distanceToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * HE terrain energy through the shared {@link BlockDamage#apply} (material thresholds, {@code csmcBlockBreaking},
     * game mode, spawn protection, world border and Fabric break vetoes, no drops). Each non-air cell within
     * {@link GrenadeTuning#HE_RADIUS}, including the cell containing the burst (e.g. a bottom slab the grenade rests
     * on), gets at most one contribution per detonation: {@link #heDamageAt} of its
     * center distance. Occlusion is decided for every cell against the pre-blast world before anything is
     * mutated: a cell only receives energy when the straight ray from the burst reaches it without first hitting
     * another collidable block. Cover therefore shields everything behind it for this whole blast even if the
     * cover itself breaks; denied/protected cover is never a pass-through. Nothing happens unless every chunk the
     * blast can reach is already loaded (never forces loads). Returns the cells that received energy.
     */
    @SuppressWarnings("deprecation") // LevelReader.hasChunksAt(x1, z1, x2, z2): the non-loading chunk check.
    static List<BlockPos> blastBlocks(ServerLevel level, ServerPlayer owner, Vec3 center) {
        int r = Mth.ceil(GrenadeTuning.HE_RADIUS);
        BlockPos origin = BlockPos.containing(center);
        List<BlockPos> exposed = new ArrayList<>();
        if (!level.hasChunksAt(origin.getX() - r, origin.getZ() - r, origin.getX() + r, origin.getZ() + r)) {
            return exposed;
        }
        double radiusSq = GrenadeTuning.HE_RADIUS * GrenadeTuning.HE_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (level.isOutsideBuildHeight(pos)
                    || Vec3.atCenterOf(pos).distanceToSqr(center) > radiusSq) {
                continue;
            }
            if (!level.getBlockState(pos).isAir() && exposedTo(level, center, pos)) {
                exposed.add(pos.immutable());
            }
        }
        exposed.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(center)));
        for (BlockPos pos : exposed) {
            double damage = heDamageAt(Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(center)));
            if (damage > 0.0) {
                BlockDamage.apply(level, owner, pos, damage);
            }
        }
        return exposed;
    }

    /** True when the ray from {@code center} to the middle of {@code pos} hits no other collidable block first. */
    static boolean exposedTo(ServerLevel level, Vec3 center, BlockPos pos) {
        BlockHitResult hit = level.clip(new ClipContext(center, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    // ---------------------------------------------------------------- Flashbang

    /**
     * Flash severity for a viewer at {@code eye} looking along {@code look}: 0 without line of sight or beyond
     * {@link GrenadeTuning#FLASH_RADIUS}; otherwise distance factor (1 within {@link GrenadeTuning#FLASH_FULL_RADIUS},
     * linear to 0 at the radius) times facing factor (1 within 60 degrees of the burst, linear down to
     * {@link GrenadeTuning#FLASH_BEHIND_FACTOR} when facing directly away).
     */
    public static double flashSeverity(ServerLevel level, Vec3 center, Vec3 eye, Vec3 look, @Nullable Entity viewer) {
        Vec3 toFlash = center.subtract(eye);
        double distance = toFlash.length();
        if (distance >= GrenadeTuning.FLASH_RADIUS) {
            return 0.0;
        }
        ClipContext clip = viewer != null
                ? new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer)
                : new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        if (level.clip(clip).getType() != HitResult.Type.MISS) {
            return 0.0;
        }
        double distanceFactor = Mth.clamp((GrenadeTuning.FLASH_RADIUS - distance)
                / (GrenadeTuning.FLASH_RADIUS - GrenadeTuning.FLASH_FULL_RADIUS), 0.0, 1.0);
        double dot = distance < 1.0E-6 ? 1.0 : look.normalize().dot(toFlash.scale(1.0 / distance));
        double facing = dot >= 0.5 ? 1.0
                : Mth.lerp((dot + 1.0) / 1.5, GrenadeTuning.FLASH_BEHIND_FACTOR, 1.0);
        return distanceFactor * facing;
    }

    /** Payload for a severity: opacity 0.35..1, up to 2 s hold and 3 s fade. */
    public static GrenadeFlashPayload flashPayload(double severity) {
        double s = Mth.clamp(severity, 0.0, 1.0);
        return new GrenadeFlashPayload((float) Math.min(1.0, 0.35 + s),
                (int) Math.round(GrenadeTuning.FLASH_MAX_HOLD_TICKS * s),
                (int) Math.round(GrenadeTuning.FLASH_MAX_FADE_TICKS * s));
    }

    /**
     * Flashbang burst: computes every non-spectator player's severity server-side and sends each affected
     * player its own whiteout. Deals no damage and modifies nothing; needs no owner.
     */
    public static List<FlashHit> flash(ServerLevel level, Vec3 center) {
        sound(level, GrenadeKind.FLASH, GrenadeSoundPayload.EVENT_DETONATE, center, 1.0F, 1.0F,
                GrenadeTuning.SOUND_RANGE_LOUD);
        level.sendParticles(ParticleTypes.FLASH, center.x, center.y, center.z, 1, 0.0, 0.0, 0.0, 0.0);
        List<FlashHit> hits = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double severity = flashSeverity(level, center, player.getEyePosition(), player.getViewVector(1.0F), player);
            if (severity < GrenadeTuning.FLASH_MIN_SEVERITY) {
                continue;
            }
            GrenadeFlashPayload payload = flashPayload(severity);
            if (ServerPlayNetworking.canSend(player, GrenadeFlashPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
            hits.add(new FlashHit(player, severity, payload));
        }
        return hits;
    }
}
