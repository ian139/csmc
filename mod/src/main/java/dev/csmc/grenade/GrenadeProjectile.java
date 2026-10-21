package dev.csmc.grenade;

import dev.csmc.item.GrenadeItem;
import dev.csmc.network.GrenadeSoundPayload;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A thrown grenade. Physics is its own (not snowball hit-and-continue): swept AABB movement via
 * {@link #move} so it never passes through blocks, per-axis bounce with {@link GrenadeTuning#RESTITUTION},
 * floor friction, rolling friction, water drag, resting, and a harmless glance off entities. Fuse/activation and
 * the single detonation run on the server only; {@link #isDetonated()} guards against any second detonation.
 *
 * <p>Saved: item (kind), flight ticks (remaining fuse), resting/active state, decoy schedule and the owner UUID.
 * Like every entity it only ticks in entity-ticking chunks, so an unloaded grenade's fuse pauses.
 */
public final class GrenadeProjectile extends Projectile implements ItemSupplier {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(GrenadeProjectile.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> DATA_SETTLED =
            SynchedEntityData.defineId(GrenadeProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final int BOUNCE_SOUND_GAP_TICKS = 3;
    private static final double BOUNCE_SOUND_SPEED = 0.1;

    private @Nullable UUID ownerId;
    private int flightTicks;
    private boolean active;
    private int activeTicks;
    private int nextReportTick;
    private int reports;
    private boolean detonated;
    private boolean landedOnFloor;
    private int lastBounceSoundTick = Integer.MIN_VALUE / 2;

    public GrenadeProjectile(EntityType<? extends GrenadeProjectile> type, Level level) {
        super(type, level);
    }

    /**
     * Spawns the server grenade for a computed {@link GrenadeThrow.Launch}. {@code stack} is the single thrown
     * item (its kind decides the behavior).
     */
    public static GrenadeProjectile launch(ServerLevel level, ServerPlayer player, ItemStack stack,
            GrenadeThrow.Launch launch) {
        GrenadeProjectile grenade = new GrenadeProjectile(GrenadeRegistry.PROJECTILE, level);
        grenade.setItem(stack);
        grenade.setOwner(player);
        Vec3 at = launch.position();
        grenade.moveTo(at.x, at.y, at.z, player.getYRot(), player.getXRot());
        grenade.setDeltaMovement(launch.velocity());
        level.addFreshEntity(grenade);
        GrenadeEffects.sound(level, grenade.getKind(), GrenadeSoundPayload.EVENT_THROW, player.getEyePosition(), 1.0F,
                1.0F, GrenadeTuning.SOUND_RANGE_QUIET);
        return grenade;
    }

    /**
     * Prediction probe only (never spawned): runs the start-of-tick fluid update of {@code Entity.baseTick} from
     * {@code position}/{@code velocity}. The probe stays on its first tick, so no splash effects play.
     */
    GrenadeArc.Fluid predictFluid(Vec3 position, Vec3 velocity) {
        setPos(position);
        setDeltaMovement(velocity);
        updateInWaterStateAndDoFluidPushing();
        return new GrenadeArc.Fluid(isInWater(), getDeltaMovement());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
        builder.define(DATA_SETTLED, false);
    }

    public void setItem(ItemStack stack) {
        entityData.set(DATA_ITEM, stack.copyWithCount(1));
    }

    @Override
    public ItemStack getItem() {
        ItemStack stack = entityData.get(DATA_ITEM);
        return stack.isEmpty() ? new ItemStack(GrenadeRegistry.item(GrenadeKind.HE)) : stack;
    }

    public GrenadeKind getKind() {
        return entityData.get(DATA_ITEM).getItem() instanceof GrenadeItem grenade ? grenade.kind() : GrenadeKind.HE;
    }

    /** Synced: at rest on the ground (client stops tumbling). */
    public boolean isSettled() {
        return entityData.get(DATA_SETTLED);
    }

    private void setSettled(boolean settled) {
        if (isSettled() != settled) {
            entityData.set(DATA_SETTLED, settled);
        }
    }

    public @Nullable UUID getOwnerId() {
        return ownerId;
    }

    /** Also records the owner UUID used for fail-closed owner resolution at detonation (see {@link GrenadeEffects}). */
    @Override
    public void setOwner(@Nullable Entity owner) {
        super.setOwner(owner);
        if (owner != null) {
            ownerId = owner.getUUID();
        }
    }

    /** Ticks since the throw (the fuse clock; saved). */
    public int getFlightTicks() {
        return flightTicks;
    }

    /** Smoke/decoy: whether the at-rest effect has started. */
    public boolean isActive() {
        return active;
    }

    /** Decoy: positional gun reports emitted so far. */
    public int getReportCount() {
        return reports;
    }

    public boolean isDetonated() {
        return detonated;
    }

    @Override
    public void tick() {
        super.tick();
        if (isRemoved()) {
            return;
        }
        physics();
        if (level() instanceof ServerLevel level && !isRemoved() && !detonated) {
            serverFuse(level);
        }
    }

    private void physics() {
        Vec3 v = getDeltaMovement();
        if (isSettled()) {
            if (v.lengthSqr() < 1.0E-4 && !level().noCollision(this, getBoundingBox().move(0.0, -0.05, 0.0))) {
                setDeltaMovement(Vec3.ZERO);
                landedOnFloor = false;
                return;
            }
            setSettled(false);
        }
        boolean server = level() instanceof ServerLevel;
        if (server) {
            Vec3 from = position();
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(level(), this, from, from.add(v),
                    getBoundingBox().expandTowards(v).inflate(1.0), this::canHitEntity);
            if (hit != null) {
                v = new Vec3(-v.x, v.y, -v.z).scale(GrenadeTuning.ENTITY_BOUNCE);
                bounceSound(v.length() / GrenadeTuning.ENTITY_BOUNCE);
            }
        }
        Vec3 start = position();
        move(MoverType.SELF, v);
        Vec3 moved = position().subtract(start);
        GrenadePhysics.Step step = GrenadePhysics.afterMove(v, moved, horizontalCollision, verticalCollision,
                onGround(), isInWater());
        landedOnFloor = step.landedOnFloor();
        if (server && step.impact() > BOUNCE_SOUND_SPEED) {
            bounceSound(step.impact());
        }
        if (step.settled()) {
            setSettled(true);
        }
        setDeltaMovement(step.velocity());
    }

    private void bounceSound(double speed) {
        if (level() instanceof ServerLevel level && tickCount - lastBounceSoundTick >= BOUNCE_SOUND_GAP_TICKS) {
            lastBounceSoundTick = tickCount;
            GrenadeEffects.sound(level, getKind(), GrenadeSoundPayload.EVENT_BOUNCE, position(),
                    (float) Mth.clamp(speed, 0.2, 1.0), 1.0F, GrenadeTuning.SOUND_RANGE_QUIET);
        }
    }

    private void serverFuse(ServerLevel level) {
        flightTicks++;
        GrenadeKind kind = getKind();
        switch (kind) {
            case HE, FLASH -> {
                if (flightTicks >= GrenadeTuning.TIMED_FUSE_TICKS) {
                    detonate(level);
                }
            }
            case MOLOTOV, INCENDIARY -> {
                if (isInWater()) {
                    detonated = true;
                    GrenadeEffects.sound(level, kind, GrenadeSoundPayload.EVENT_EXTINGUISH, position(), 1.0F, 1.0F,
                            GrenadeTuning.SOUND_RANGE_QUIET);
                    discard();
                } else if (landedOnFloor || flightTicks >= GrenadeTuning.FIRE_FUSE_TICKS) {
                    detonate(level);
                }
            }
            case SMOKE -> {
                if (isSettled() || flightTicks >= GrenadeTuning.REST_GUARD_TICKS) {
                    detonate(level);
                }
            }
            case DECOY -> tickDecoy(level);
        }
    }

    private void tickDecoy(ServerLevel level) {
        if (!active) {
            if (!isSettled() && flightTicks < GrenadeTuning.REST_GUARD_TICKS) {
                return;
            }
            active = true;
            activeTicks = 0;
            nextReportTick = 0;
        }
        activeTicks++;
        if (activeTicks >= GrenadeTuning.DECOY_DURATION_TICKS) {
            detonate(level);
            return;
        }
        if (activeTicks >= nextReportTick) {
            reports++;
            GrenadeEffects.sound(level, GrenadeKind.DECOY, GrenadeSoundPayload.EVENT_DECOY, position(), 1.0F, 1.0F,
                    GrenadeTuning.SOUND_RANGE_LOUD);
            nextReportTick = activeTicks + GrenadeTuning.DECOY_MIN_GAP_TICKS
                    + random.nextInt(GrenadeTuning.DECOY_MAX_GAP_TICKS - GrenadeTuning.DECOY_MIN_GAP_TICKS + 1);
        }
    }

    /** Runs this grenade's effect exactly once and removes it. Server only; later calls do nothing. */
    public void detonate(ServerLevel level) {
        if (detonated || isRemoved()) {
            return;
        }
        detonated = true;
        GrenadeKind kind = getKind();
        Vec3 center = position().add(0.0, getBbHeight() * 0.5, 0.0);
        switch (kind) {
            case HE -> GrenadeEffects.explodeHe(level, this, GrenadeEffects.resolveOwner(level, ownerId), center);
            case FLASH -> GrenadeEffects.flash(level, center);
            case SMOKE -> {
                GrenadeEffects.sound(level, kind, GrenadeSoundPayload.EVENT_DETONATE, center, 1.0F, 1.0F,
                        GrenadeTuning.SOUND_RANGE_LOUD);
                GrenadeCloud.spawnSmoke(level, position());
            }
            case MOLOTOV, INCENDIARY -> {
                GrenadeEffects.sound(level, kind, GrenadeSoundPayload.EVENT_DETONATE, center, 1.0F, 1.0F,
                        GrenadeTuning.SOUND_RANGE_LOUD);
                GrenadeCloud.spawnFire(level, kind, position(), ownerId);
            }
            case DECOY -> {
                GrenadeEffects.sound(level, kind, GrenadeSoundPayload.EVENT_DETONATE, center, 0.6F, 1.0F,
                        GrenadeTuning.SOUND_RANGE_QUIET);
                level.sendParticles(ParticleTypes.SMOKE, center.x, center.y, center.z, 8, 0.1, 0.1, 0.1, 0.02);
            }
        }
        discard();
    }

    @Override
    protected double getDefaultGravity() {
        return GrenadeTuning.GRAVITY;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        double size = getBoundingBox().getSize() * 4.0;
        if (Double.isNaN(size)) {
            size = 4.0;
        }
        size *= 64.0;
        return distanceSq < size * size;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ItemStack item = entityData.get(DATA_ITEM);
        if (!item.isEmpty()) {
            tag.put("Item", item.save(registryAccess()));
        }
        if (ownerId != null) {
            tag.putUUID("GrenadeOwner", ownerId);
        }
        tag.putInt("Flight", flightTicks);
        tag.putBoolean("Settled", isSettled());
        tag.putBoolean("Active", active);
        tag.putInt("ActiveTicks", activeTicks);
        tag.putInt("NextReport", nextReportTick);
        tag.putInt("Reports", reports);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setItem(tag.contains("Item") ? ItemStack.parseOptional(registryAccess(), tag.getCompound("Item")) : ItemStack.EMPTY);
        ownerId = tag.hasUUID("GrenadeOwner") ? tag.getUUID("GrenadeOwner") : null;
        flightTicks = Math.max(0, tag.getInt("Flight"));
        setSettled(tag.getBoolean("Settled"));
        active = tag.getBoolean("Active");
        activeTicks = Math.max(0, tag.getInt("ActiveTicks"));
        nextReportTick = Math.max(0, tag.getInt("NextReport"));
        reports = Math.max(0, tag.getInt("Reports"));
    }
}
