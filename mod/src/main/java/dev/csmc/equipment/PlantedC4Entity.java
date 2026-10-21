package dev.csmc.equipment;

import dev.csmc.ballistics.Ballistics;
import dev.csmc.ballistics.BlockDamage;
import dev.csmc.grenade.GrenadeCloud;
import dev.csmc.grenade.GrenadeEffects;
import dev.csmc.grenade.GrenadeProjectile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * A planted C4 charge. Immobile, indestructible and pickable (so vanilla's entity interaction reaches
 * {@link #interact}). Server logic only: the fuse counts down {@link #FUSE_TICKS} ticks while the entity ticks, so
 * like every entity it pauses in unloaded chunks; it beeps at an accelerating rate and detonates exactly once
 * unless defused first.
 *
 * <p>Defusing: one player at a time, started through {@link EquipmentSystem#startDefuse} (from {@link #interact}).
 * Takes {@link #DEFUSE_TICKS} bare-handed or {@link #KIT_DEFUSE_TICKS} when a {@link EquipmentRegistry#DEFUSE_KIT}
 * is anywhere in the defuser's inventory at start (the kit is not consumed). The speed is fixed at start; a kit
 * defuse whose kit leaves the inventory is interrupted. Every tick the defuser must still be alive, not a
 * spectator, in this level, aiming at the bomb within interaction range with no block in between
 * ({@link #canDefuse}), and hold an active
 * {@link EquipmentSystem#isDefusing} session; any failure, or {@link #cancelDefuse}, interrupts and resets progress
 * to zero. The fuse keeps running during a defuse.
 *
 * <p>Saved: owner, remaining fuse, beep schedule, defused/detonated state, and defuser/progress. A saved defuse is
 * only resumed when its player still holds a live session on the first tick after loading (normally it is not,
 * so it resets).
 *
 * <p>Harm is fail-closed exactly like grenades ({@link GrenadeEffects#resolveOwner}/{@link GrenadeEffects#mayHarm}):
 * an owner who disconnected, is in another dimension or is unknown makes the explosion purely cosmetic.
 */
public final class PlantedC4Entity extends Entity {
    /** Fuse length: 40 s. */
    public static final int FUSE_TICKS = 800;
    /** Bare-handed defuse: 10 s. */
    public static final int DEFUSE_TICKS = 200;
    /** Defuse with a kit in the inventory: 5 s. */
    public static final int KIT_DEFUSE_TICKS = 100;
    /** Blast radius in blocks; nothing is harmed beyond it. */
    public static final double RADIUS = 8.0;

    /** Adaptation value: peak blast in CS-scale damage units, converted with {@link Ballistics#CS_TO_MC_HEALTH}. */
    static final double CS_PEAK_DAMAGE = 500.0;
    /** Adaptation value: gaussian width chosen so {@link #RADIUS} is the 3-sigma point. */
    static final double SIGMA = RADIUS / 3.0;
    /** Slowest beep gap (start of the fuse) and fastest (last moments). */
    static final int BEEP_SLOWEST_TICKS = 20;
    static final int BEEP_FASTEST_TICKS = 2;
    /** Defuse progress event cadence. */
    static final int PROGRESS_EVENT_TICKS = 10;
    /** Growth of the bomb box the defuser's look ray must hit (forgiving aim, like a pick radius). */
    static final double AIM_INFLATE = 0.25;
    /** Blocks/tick of push at full strength (point blank, fully exposed), scaled like the damage. */
    static final double KNOCKBACK = 1.5;
    static final String EVENT_ID = "c4";

    private static final EntityDataAccessor<Integer> DATA_FUSE =
            SynchedEntityData.defineId(PlantedC4Entity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_DEFUSE_PROGRESS =
            SynchedEntityData.defineId(PlantedC4Entity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_DEFUSED =
            SynchedEntityData.defineId(PlantedC4Entity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> DATA_DEFUSER =
            SynchedEntityData.defineId(PlantedC4Entity.class, EntityDataSerializers.OPTIONAL_UUID);

    private @Nullable UUID ownerId;
    /** Elapsed ticks of the current defuse. */
    private int defuseTicks;
    /** Ticks the current defuse needs ({@link #DEFUSE_TICKS} or {@link #KIT_DEFUSE_TICKS}); 0 when none. */
    private int defuseRequired;
    /** Fuse value at (or below) which the next beep plays. */
    private int nextBeepAt = FUSE_TICKS;
    private boolean detonated;

    public PlantedC4Entity(EntityType<? extends PlantedC4Entity> type, Level level) {
        super(type, level);
        blocksBuilding = true;
    }

    /**
     * Plants a charge owned by {@code player} at {@code pos}, facing the player's yaw. Returns null when its box
     * would intersect block collision, it would overlap another live planted C4, or the level refuses the entity.
     */
    public static @Nullable PlantedC4Entity plant(ServerLevel level, ServerPlayer player, Vec3 pos) {
        PlantedC4Entity bomb = new PlantedC4Entity(EquipmentRegistry.PLANTED_C4, level);
        bomb.ownerId = player.getUUID();
        bomb.moveTo(pos.x, pos.y, pos.z, player.getYRot(), 0.0F);
        if (!level.noCollision(bomb)
                || !level.getEntitiesOfClass(PlantedC4Entity.class, bomb.getBoundingBox(), Entity::isAlive).isEmpty()) {
            return null;
        }
        return level.addFreshEntity(bomb) ? bomb : null;
    }

    /**
     * Health removed at {@code distance} blocks before exposure: a CS-style gaussian falloff
     * {@code CS_PEAK_DAMAGE * exp(-d^2 / (2 * SIGMA^2))} in CS-scale units, times
     * {@link Ballistics#CS_TO_MC_HEALTH}, cut to 0 beyond {@link #RADIUS}. The peak, width and radius are this
     * mod's adaptation values, not a claim about any CS version's exact numbers.
     */
    public static double damageAt(double distance) {
        return csDamageAt(distance) * Ballistics.CS_TO_MC_HEALTH;
    }

    /** {@link #damageAt} in CS damage units (what {@link BlockDamage#apply} takes). */
    static double csDamageAt(double distance) {
        if (!(distance <= RADIUS)) {
            return 0.0; // beyond the radius, or NaN
        }
        distance = Math.max(0.0, distance);
        return CS_PEAK_DAMAGE * Math.exp(-(distance * distance) / (2.0 * SIGMA * SIGMA));
    }

    /** Gap until the next beep for {@code fuseLeft} remaining ticks: linear from 20 ticks down to 2 near zero. */
    static int beepInterval(int fuseLeft) {
        double left = Mth.clamp(fuseLeft / (double) FUSE_TICKS, 0.0, 1.0);
        return Math.max(BEEP_FASTEST_TICKS,
                (int) Math.round(BEEP_FASTEST_TICKS + (BEEP_SLOWEST_TICKS - BEEP_FASTEST_TICKS) * left));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_FUSE, FUSE_TICKS);
        builder.define(DATA_DEFUSE_PROGRESS, 0.0F);
        builder.define(DATA_DEFUSED, false);
        builder.define(DATA_DEFUSER, Optional.empty());
    }

    /** Synced: remaining fuse ticks. */
    public int getFuseTicks() {
        return entityData.get(DATA_FUSE);
    }

    /** Synced: current defuse progress in [0, 1]; 0 when nobody is defusing. */
    public float getDefuseProgress() {
        return entityData.get(DATA_DEFUSE_PROGRESS);
    }

    /** Synced: defused. A completed defuse sets this, then removes the bomb without exploding. */
    public boolean isDefused() {
        return entityData.get(DATA_DEFUSED);
    }

    public @Nullable UUID getOwnerId() {
        return ownerId;
    }

    /** Synced: the player currently defusing, or null. */
    public @Nullable UUID getDefuserId() {
        return entityData.get(DATA_DEFUSER).orElse(null);
    }

    /** The only defuser mutation: updates the synced (and authoritative) defuser. */
    private void setDefuser(@Nullable UUID defuser) {
        entityData.set(DATA_DEFUSER, Optional.ofNullable(defuser));
    }

    /** Server: elapsed ticks of the current defuse (0 when none). */
    public int getDefuseTicks() {
        return defuseTicks;
    }

    public boolean isDetonated() {
        return detonated;
    }

    private void setFuse(int ticks) {
        entityData.set(DATA_FUSE, Math.max(0, ticks));
    }

    private void setProgress(float progress) {
        entityData.set(DATA_DEFUSE_PROGRESS, Mth.clamp(progress, 0.0F, 1.0F));
    }

    // ---------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || isRemoved()) {
            return;
        }
        if (detonated || isDefused()) {
            discard(); // terminal state (e.g. loaded from NBT): never linger as an inert ghost
            return;
        }
        if (getDefuserId() != null) {
            tickDefuse(level);
            if (isRemoved()) {
                return;
            }
        }
        int fuse = getFuseTicks() - 1;
        setFuse(fuse);
        if (fuse <= 0) {
            detonate();
            return;
        }
        if (fuse <= nextBeepAt) {
            nextBeepAt = fuse - beepInterval(fuse);
            EquipmentSystem.event(level, EVENT_ID, "beep", getId(), position(), 1.0F - fuse / (float) FUSE_TICKS);
        }
    }

    private void tickDefuse(ServerLevel level) {
        ServerPlayer defuser = level.getServer().getPlayerList().getPlayer(getDefuserId());
        if (defuser == null || !canDefuse(defuser) || !EquipmentSystem.isDefusing(defuser, this)
                || defuseRequired <= 0 || (defuseRequired == KIT_DEFUSE_TICKS && !hasKit(defuser))) {
            interruptDefuse(level);
            return;
        }
        defuseTicks++;
        setProgress(defuseTicks / (float) defuseRequired);
        if (defuseTicks >= defuseRequired) {
            entityData.set(DATA_DEFUSED, true);
            setProgress(1.0F);
            setDefuser(null);
            defuseTicks = 0;
            defuseRequired = 0;
            EquipmentSystem.event(level, EVENT_ID, "disarm_finish", getId(), position(), 1.0F);
            discard();
        } else if (defuseTicks % PROGRESS_EVENT_TICKS == 0) {
            EquipmentSystem.event(level, EVENT_ID, "progress", getId(), position(), getDefuseProgress());
        }
    }

    // ---------------------------------------------------------------- defuse API

    /**
     * Starts (or, for the same player, keeps) a defuse. False when the bomb is defused/detonated/removed, the
     * player fails {@link #canDefuse}, or another valid player is already defusing. Speed is fixed now from kit
     * possession.
     */
    public boolean startDefuse(ServerPlayer player) {
        if (!(level() instanceof ServerLevel level) || isRemoved() || detonated || isDefused() || !canDefuse(player)) {
            return false;
        }
        UUID currentId = getDefuserId();
        if (currentId != null) {
            if (currentId.equals(player.getUUID())) {
                return true;
            }
            ServerPlayer current = level.getServer().getPlayerList().getPlayer(currentId);
            if (current != null && canDefuse(current) && EquipmentSystem.isDefusing(current, this)) {
                return false;
            }
            interruptDefuse(level);
        }
        setDefuser(player.getUUID());
        defuseTicks = 0;
        defuseRequired = hasKit(player) ? KIT_DEFUSE_TICKS : DEFUSE_TICKS;
        setProgress(0.0F);
        EquipmentSystem.event(level, EVENT_ID, "disarm_start", getId(), position(), 0.0F);
        return true;
    }

    /** Interrupts {@code player}'s defuse (progress resets). Does nothing if they are not the defuser. */
    public void cancelDefuse(ServerPlayer player) {
        if (level() instanceof ServerLevel level && player.getUUID().equals(getDefuserId())) {
            interruptDefuse(level);
        }
    }

    private void interruptDefuse(ServerLevel level) {
        if (getDefuserId() == null) {
            return;
        }
        float progress = getDefuseProgress();
        setDefuser(null);
        defuseTicks = 0;
        defuseRequired = 0;
        setProgress(0.0F);
        EquipmentSystem.event(level, EVENT_ID, "cancel", getId(), position(), progress);
    }

    /**
     * Whether {@code player} may (continue to) defuse: alive, not a spectator, not removed or disconnected, in
     * this level, and aiming at it: the eye is inside the bomb box grown by {@link #AIM_INFLATE}, or the look ray
     * hits that grown box within {@link Player#entityInteractionRange}. Separately, the eye must see the physical
     * (ungrown) bomb box: no collidable block may lie between the eye and the point where the straight line from
     * the eye to the physical box's center enters it, so neither the grown aim box nor an eye inside it can see
     * through cover. Does not check the {@link EquipmentSystem} use session.
     */
    public boolean canDefuse(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.isRemoved() || player.hasDisconnected()
                || player.level() != level() || isRemoved()) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        AABB physical = getBoundingBox();
        AABB aim = physical.inflate(AIM_INFLATE);
        if (!aim.contains(eye)) {
            Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
            if (aim.clip(eye, end).isEmpty()) {
                return false;
            }
        }
        Vec3 center = physical.getCenter();
        Vec3 target = physical.clip(eye, center).orElse(center);
        return level().clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    /** A {@link EquipmentRegistry#DEFUSE_KIT} in any inventory slot (main, armor, offhand). */
    static boolean hasKit(Player player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(EquipmentRegistry.DEFUSE_KIT)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (isDefused() || detonated) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            return EquipmentSystem.startDefuse(serverPlayer, this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- detonation

    /**
     * Explodes once and removes the bomb. Server only; does nothing when defused, already detonated or removed.
     * Entities within {@link #RADIUS}: {@link #damageAt} of the distance to their box times vanilla ray exposure
     * ({@link Explosion#getSeenPercent}), as explosion damage attributed to the owner (self hits use an
     * unattributed explosion source so PvP/team vetoes cannot cancel them), plus knockback. Terrain only when
     * {@link EquipmentRegistry#C4_BREAKING} is true, through {@link BlockDamage#apply}. Needs a resolved owner for
     * any harm.
     */
    public void detonate() {
        if (!(level() instanceof ServerLevel level) || detonated || isRemoved() || isDefused()) {
            return;
        }
        detonated = true;
        Vec3 center = position().add(0.0, getBbHeight() * 0.5, 0.0);
        if (getDefuserId() != null) {
            setDefuser(null);
            defuseTicks = 0;
            defuseRequired = 0;
        }
        setFuse(0);
        EquipmentSystem.event(level, EVENT_ID, "explode", getId(), center, 1.0F);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 2, 0.5, 0.5, 0.5, 0.0);
        ServerPlayer owner = GrenadeEffects.resolveOwner(level, ownerId);
        if (owner != null) {
            harmEntities(level, owner, center);
            if (level.getGameRules().getBoolean(EquipmentRegistry.C4_BREAKING)) {
                blastBlocks(level, owner, center);
            }
        }
        discard();
    }

    private void harmEntities(ServerLevel level, ServerPlayer owner, Vec3 center) {
        DamageSource source = level.damageSources().explosion(this, owner);
        DamageSource selfSource = level.damageSources().explosion(this, null);
        List<Entity> targets = level.getEntities(this, new AABB(center, center).inflate(RADIUS),
                entity -> entity.isAlive() && !entity.isSpectator() && !(entity instanceof PlantedC4Entity)
                        && !(entity instanceof GrenadeProjectile) && !(entity instanceof GrenadeCloud));
        for (Entity target : targets) {
            double health = damageAt(distanceToBox(center, target.getBoundingBox()));
            if (health <= 0.0 || !GrenadeEffects.mayHarm(owner, target)) {
                continue;
            }
            double exposure = Explosion.getSeenPercent(center, target);
            if (exposure <= 0.0) {
                continue;
            }
            if (target.hurt(target == owner ? selfSource : source, (float) (health * exposure))) {
                knockback(target, center, health / damageAt(0.0) * exposure);
            }
        }
    }

    private static void knockback(Entity target, Vec3 center, double strength) {
        Vec3 away = target.getBoundingBox().getCenter().subtract(center);
        if (away.lengthSqr() < 1.0E-6) {
            away = new Vec3(0.0, 1.0, 0.0);
        }
        double resist = target instanceof LivingEntity living
                ? living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE) : 0.0;
        double speed = KNOCKBACK * strength * (1.0 - Mth.clamp(resist, 0.0, 1.0));
        if (speed > 0.0) {
            target.setDeltaMovement(target.getDeltaMovement().add(away.normalize().scale(speed)));
            target.hurtMarked = true;
        }
    }

    private static double distanceToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Terrain energy, same contract as the HE blast: every non-air cell within {@link #RADIUS} whose center the
     * straight ray from the burst reaches without first hitting another collidable block (decided against the
     * pre-blast world) gets one {@link #csDamageAt} contribution through {@link BlockDamage#apply} (material
     * thresholds, csmcBlockBreaking, protection, world border, no drops). Skipped unless every reachable chunk
     * is already loaded.
     */
    @SuppressWarnings("deprecation") // LevelReader.hasChunksAt(x1, z1, x2, z2): the non-loading chunk check.
    private static void blastBlocks(ServerLevel level, ServerPlayer owner, Vec3 center) {
        int r = Mth.ceil(RADIUS);
        BlockPos origin = BlockPos.containing(center);
        if (!level.hasChunksAt(origin.getX() - r, origin.getZ() - r, origin.getX() + r, origin.getZ() + r)) {
            return;
        }
        double radiusSq = RADIUS * RADIUS;
        List<BlockPos> exposed = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (level.isOutsideBuildHeight(pos) || Vec3.atCenterOf(pos).distanceToSqr(center) > radiusSq
                    || level.getBlockState(pos).isAir()) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(center, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
                exposed.add(pos.immutable());
            }
        }
        exposed.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(center)));
        for (BlockPos pos : exposed) {
            double damage = csDamageAt(Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(center)));
            if (damage > 0.0) {
                BlockDamage.apply(level, owner, pos, damage);
            }
        }
    }

    // ---------------------------------------------------------------- physical behavior

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean canUsePortal(boolean allowVehicles) {
        return false;
    }

    @Override
    public void push(double x, double y, double z) {}

    // ---------------------------------------------------------------- save

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerId != null) {
            tag.putUUID("C4Owner", ownerId);
        }
        tag.putInt("Fuse", getFuseTicks());
        tag.putInt("NextBeep", nextBeepAt);
        tag.putBoolean("Defused", isDefused());
        tag.putBoolean("Detonated", detonated);
        UUID defuser = getDefuserId();
        if (defuser != null) {
            tag.putUUID("Defuser", defuser);
            tag.putInt("DefuseTicks", defuseTicks);
            tag.putInt("DefuseRequired", defuseRequired);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        ownerId = tag.hasUUID("C4Owner") ? tag.getUUID("C4Owner") : null;
        setFuse(tag.contains("Fuse") ? Mth.clamp(tag.getInt("Fuse"), 0, FUSE_TICKS) : FUSE_TICKS);
        nextBeepAt = tag.contains("NextBeep") ? Math.min(tag.getInt("NextBeep"), FUSE_TICKS) : getFuseTicks();
        entityData.set(DATA_DEFUSED, tag.getBoolean("Defused"));
        detonated = tag.getBoolean("Detonated");
        int required = tag.getInt("DefuseRequired");
        if (tag.hasUUID("Defuser") && !isDefused() && (required == DEFUSE_TICKS || required == KIT_DEFUSE_TICKS)) {
            setDefuser(tag.getUUID("Defuser"));
            defuseRequired = required;
            defuseTicks = Mth.clamp(tag.getInt("DefuseTicks"), 0, required - 1);
            setProgress(defuseTicks / (float) required);
        } else {
            setDefuser(null);
            defuseTicks = 0;
            defuseRequired = 0;
            setProgress(isDefused() ? 1.0F : 0.0F);
        }
    }
}
