package dev.csmc.grenade;

import dev.csmc.ballistics.Ballistics;
import dev.csmc.ballistics.BlockDamage;
import dev.csmc.network.GrenadeSoundPayload;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent grenade effect volume: a smoke cloud ({@link GrenadeKind#SMOKE}) or a burning pool
 * ({@link GrenadeKind#MOLOTOV}/{@link GrenadeKind#INCENDIARY}). Kind, max radius, age and duration are synced
 * entity data and saved; the entity position is the bottom center of the volume.
 *
 * <p><b>Smoke</b>: a cylinder that grows to {@link GrenadeTuning#SMOKE_RADIUS} over
 * {@link GrenadeTuning#SMOKE_GROW_TICKS} and lasts {@link GrenadeTuning#SMOKE_DURATION_TICKS}; it removes any
 * grenade fire cells inside it (no water, no blocks placed). Rendering/obscuring is client-side.
 *
 * <p><b>Fire</b>: a bounded set of burning cells found by a flood fill over connected walkable ground from the
 * landing cell (at most {@link GrenadeTuning#FIRE_RADIUS} horizontally, one block of step up/down, never through
 * a wall or onto another floor, at most {@link GrenadeTuning#FIRE_MAX_CELLS}). No vanilla fire blocks are placed,
 * so nothing spreads. Entities whose feet are in a burning cell take owner-attributed fire damage (PvP/team
 * guarded) and a short after-burn; flammable blocks touching the pool take shared {@link BlockDamage}. Water in a
 * cell or a smoke cloud removes cells; the pool is extinguished when none remain. Damage and block burning need a
 * resolved owner.
 */
public final class GrenadeCloud extends Entity {
    public static final float SMOKE_HEIGHT = GrenadeTuning.SMOKE_HEIGHT;
    public static final float FIRE_HEIGHT = GrenadeTuning.FIRE_HEIGHT;
    public static final float SMOKE_RADIUS = GrenadeTuning.SMOKE_RADIUS;
    public static final float FIRE_RADIUS = GrenadeTuning.FIRE_RADIUS;
    private static final double SURFACE_EPSILON = 1.0E-3;
    private static final double CONTACT_TOLERANCE = 0.01;
    public static final int SMOKE_GROW_TICKS = GrenadeTuning.SMOKE_GROW_TICKS;

    private static final EntityDataAccessor<Byte> DATA_KIND =
            SynchedEntityData.defineId(GrenadeCloud.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_MAX_RADIUS =
            SynchedEntityData.defineId(GrenadeCloud.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_AGE =
            SynchedEntityData.defineId(GrenadeCloud.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DURATION =
            SynchedEntityData.defineId(GrenadeCloud.class, EntityDataSerializers.INT);

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int CELL_CHECK_INTERVAL_TICKS = 5;
    private static final int LOOP_INTERVAL_TICKS = 20;

    private @Nullable UUID owner;
    /** Burning cells (fire only); the feet cell an entity must occupy to burn. Server-side, saved. */
    private final LongSet cells = new LongLinkedOpenHashSet();
    private boolean cellsInitialized;

    public GrenadeCloud(EntityType<? extends GrenadeCloud> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** A smoke cloud resting at {@code pos}; it immediately clears grenade fire inside its volume. */
    public static GrenadeCloud spawnSmoke(ServerLevel level, Vec3 pos) {
        GrenadeCloud cloud = new GrenadeCloud(GrenadeRegistry.CLOUD, level);
        cloud.setKind(GrenadeKind.SMOKE);
        cloud.moveTo(pos.x, pos.y, pos.z);
        level.addFreshEntity(cloud);
        return cloud;
    }

    /**
     * A fire pool for {@code kind} whose flood fill starts at the cell containing {@code pos}, or null when it
     * cannot burn there (no floor within {@link GrenadeTuning#FIRE_AIRBURST_FLOOR_BLOCKS}, water, or already
     * fully inside smoke). Water/smoke failures send {@code extinguish}.
     */
    public static @Nullable GrenadeCloud spawnFire(ServerLevel level, GrenadeKind kind, Vec3 pos, @Nullable UUID owner) {
        if (!kind.isFire()) {
            throw new IllegalArgumentException("not a fire grenade: " + kind);
        }
        BlockPos start = findFireOrigin(level, pos);
        if (start == null) {
            return null;
        }
        if (isWater(level, start)) {
            GrenadeEffects.sound(level, kind, GrenadeSoundPayload.EVENT_EXTINGUISH, pos, 1.0F, 1.0F,
                    GrenadeTuning.SOUND_RANGE_QUIET);
            return null;
        }
        GrenadeCloud cloud = new GrenadeCloud(GrenadeRegistry.CLOUD, level);
        cloud.setKind(kind);
        cloud.owner = owner;
        cloud.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        cloud.cells.addAll(floodFill(level, start));
        cloud.cellsInitialized = true;
        for (GrenadeCloud smoke : level.getEntitiesOfClass(GrenadeCloud.class,
                cloud.reach().inflate(GrenadeTuning.SMOKE_RADIUS + 1.0), c -> c.getKind() == GrenadeKind.SMOKE)) {
            smoke.clearFireCells(cloud);
        }
        if (cloud.cells.isEmpty()) {
            GrenadeEffects.sound(level, kind, GrenadeSoundPayload.EVENT_EXTINGUISH, cloud.position(), 1.0F, 1.0F,
                    GrenadeTuning.SOUND_RANGE_QUIET);
            return null;
        }
        level.addFreshEntity(cloud);
        return cloud;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, (byte) GrenadeKind.SMOKE.ordinal());
        builder.define(DATA_MAX_RADIUS, GrenadeTuning.SMOKE_RADIUS);
        builder.define(DATA_AGE, 0);
        builder.define(DATA_DURATION, GrenadeTuning.SMOKE_DURATION_TICKS);
    }

    private void setKind(GrenadeKind kind) {
        GrenadeKind cloudKind = kind == GrenadeKind.SMOKE || kind.isFire() ? kind : GrenadeKind.SMOKE;
        entityData.set(DATA_KIND, (byte) cloudKind.ordinal());
        entityData.set(DATA_MAX_RADIUS, authoredRadius(cloudKind));
        entityData.set(DATA_DURATION, authoredDuration(cloudKind));
    }

    private static float authoredRadius(GrenadeKind kind) {
        return kind.isFire() ? GrenadeTuning.FIRE_RADIUS : GrenadeTuning.SMOKE_RADIUS;
    }

    private static int authoredDuration(GrenadeKind kind) {
        return kind.isFire() ? GrenadeTuning.FIRE_DURATION_TICKS : GrenadeTuning.SMOKE_DURATION_TICKS;
    }

    /** {@link GrenadeKind#SMOKE}, {@link GrenadeKind#MOLOTOV} or {@link GrenadeKind#INCENDIARY}. */
    public GrenadeKind getKind() {
        return GrenadeKind.byOrdinal(entityData.get(DATA_KIND));
    }

    /** Current horizontal radius: smoke grows linearly to its max over {@link #SMOKE_GROW_TICKS}; fire is constant. */
    public float getRadius() {
        float max = getMaxRadius();
        if (getKind() != GrenadeKind.SMOKE) {
            return max;
        }
        return max * Mth.clamp((float) getAgeTicks() / GrenadeTuning.SMOKE_GROW_TICKS, 0.0F, 1.0F);
    }

    public float getMaxRadius() {
        return entityData.get(DATA_MAX_RADIUS);
    }

    /** Volume height above the entity position. */
    public float getHeight() {
        return getKind() == GrenadeKind.SMOKE ? GrenadeTuning.SMOKE_HEIGHT : GrenadeTuning.FIRE_HEIGHT;
    }

    public int getAgeTicks() {
        return entityData.get(DATA_AGE);
    }

    public int getDurationTicks() {
        return entityData.get(DATA_DURATION);
    }

    public @Nullable UUID getOwnerId() {
        return owner;
    }

    /** Burning cells (packed {@link BlockPos}); empty for smoke. Copy, server-side meaningful only. */
    public LongSet getFireCells() {
        return new LongOpenHashSet(cells);
    }

    /** Server-side test/inspection: whether the cell at {@code pos} is burning. */
    public boolean isBurning(BlockPos pos) {
        return cells.contains(pos.asLong());
    }

    /** Bounding region of the effect volume. */
    public AABB reach() {
        float r = getMaxRadius();
        return new AABB(getX() - r, getY() - 1.0, getZ() - r, getX() + r, getY() + getHeight() + 1.0, getZ() + r);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        int age = getAgeTicks() + 1;
        entityData.set(DATA_AGE, age);
        if (age >= getDurationTicks()) {
            discard();
            return;
        }
        if (getKind() == GrenadeKind.SMOKE) {
            tickSmoke(level);
        } else {
            tickFire(level, age);
        }
    }

    private void tickSmoke(ServerLevel level) {
        for (GrenadeCloud fire : level.getEntitiesOfClass(GrenadeCloud.class, reach().inflate(GrenadeTuning.FIRE_RADIUS + 1.0),
                c -> c.getKind().isFire() && c.isAlive())) {
            clearFireCells(fire);
            if (fire.cells.isEmpty()) {
                fire.extinguish();
            }
        }
    }

    /** Removes {@code fire}'s cells inside this smoke's current volume. */
    private void clearFireCells(GrenadeCloud fire) {
        double radius = getRadius() + 0.5;
        double radiusSq = radius * radius;
        double bottom = getY() - 1.0;
        double top = getY() + getHeight();
        removeCells(fire.cells, cell -> {
            double dx = cell.getX() + 0.5 - getX();
            double dz = cell.getZ() + 0.5 - getZ();
            return dx * dx + dz * dz <= radiusSq && cell.getY() >= bottom && cell.getY() <= top;
        });
    }

    private static void removeCells(LongSet set, java.util.function.Predicate<BlockPos> remove) {
        LongIterator it = set.iterator();
        while (it.hasNext()) {
            if (remove.test(BlockPos.of(it.nextLong()))) {
                it.remove();
            }
        }
    }

    private void tickFire(ServerLevel level, int age) {
        if (!cellsInitialized) {
            cellsInitialized = true;
            if (cells.isEmpty()) {
                cells.addAll(floodFill(level, blockPosition()));
            }
        }
        if (age % CELL_CHECK_INTERVAL_TICKS == 0) {
            removeCells(cells, cell -> level.isLoaded(cell) && (isWater(level, cell) || !isFireCell(level, cell)));
        }
        if (cells.isEmpty()) {
            extinguish();
            return;
        }
        if (age % 2 == 0) {
            long[] packed = cells.toLongArray();
            BlockPos cell = BlockPos.of(packed[random.nextInt(packed.length)]);
            level.sendParticles(ParticleTypes.FLAME, cell.getX() + random.nextDouble(), cell.getY() + 0.1,
                    cell.getZ() + random.nextDouble(), 2, 0.1, 0.1, 0.1, 0.01);
        }
        if (age % LOOP_INTERVAL_TICKS == 1) {
            GrenadeEffects.sound(level, getKind(), GrenadeSoundPayload.EVENT_LOOP, position(), 1.0F, 1.0F,
                    GrenadeTuning.SOUND_RANGE_QUIET);
        }
        ServerPlayer resolved = GrenadeEffects.resolveOwner(level, owner);
        if (resolved == null) {
            return;
        }
        if (age % GrenadeTuning.FIRE_DAMAGE_INTERVAL_TICKS == 0) {
            burnEntities(level, resolved);
        }
        if (age % GrenadeTuning.FIRE_BLOCK_INTERVAL_TICKS == 0) {
            burnBlocks(level, resolved);
        }
    }

    private void burnEntities(ServerLevel level, ServerPlayer resolved) {
        DamageSource source = level.damageSources().source(DamageTypes.IN_FIRE, this, resolved);
        // Already authorized by mayHarm; not player-attributed so vanilla's PvP/team veto cannot cancel it.
        DamageSource selfSource = level.damageSources().source(DamageTypes.IN_FIRE, this, null);
        float health = (float) (GrenadeTuning.FIRE_DAMAGE * Ballistics.CS_TO_MC_HEALTH);
        for (Entity target : burnTargets(resolved)) {
            if (target.hurt(target == resolved ? selfSource : source, health) && !target.fireImmune()) {
                target.setRemainingFireTicks(Math.max(target.getRemainingFireTicks(), GrenadeTuning.FIRE_AFTERBURN_TICKS));
            }
        }
    }

    /**
     * Entities the pool may burn this pulse for {@code resolved}: feet in a burning cell
     * ({@link #standsInFire}) and allowed by {@link GrenadeEffects#mayHarm} (PvP and team friendly fire).
     */
    public List<Entity> burnTargets(ServerPlayer resolved) {
        return level().getEntities(this, reach(), e -> e.isAlive() && !e.isSpectator()
                && !(e instanceof GrenadeCloud) && !(e instanceof GrenadeProjectile)
                && standsInFire(e) && GrenadeEffects.mayHarm(resolved, e));
    }

    /**
     * True when the entity's feet touch a burning cell: the cell is in a column under the entity, at its feet
     * layer or one above (a cell over a partial floor such as a bottom slab or stair), and the feet are between the
     * local supporting surface under the entity's footprint in that column and {@link GrenadeTuning#FIRE_HEIGHT}
     * above it. Entities below that surface (another floor, beside a full-block ledge) never match.
     */
    public boolean standsInFire(Entity entity) {
        AABB box = entity.getBoundingBox();
        double feet = box.minY;
        int feetCell = Mth.floor(feet + SURFACE_EPSILON);
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-4); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-4); z++) {
                for (int y = feetCell; y <= feetCell + 1; y++) {
                    if (cells.contains(BlockPos.asLong(x, y, z))) {
                        double surface = supportTop(new BlockPos(x, y, z), box);
                        if (feet >= surface - CONTACT_TOLERANCE && feet < surface + GrenadeTuning.FIRE_HEIGHT) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * World Y of the highest collision box of the block under {@code cell} that lies horizontally under
     * {@code footprint}; NaN (no contact) when nothing supports that footprint in this column.
     */
    private double supportTop(BlockPos cell, AABB footprint) {
        BlockPos below = cell.below();
        if (!level().isLoaded(below)) {
            return Double.NaN;
        }
        VoxelShape shape = level().getBlockState(below).getCollisionShape(level(), below);
        double top = Double.NaN;
        for (AABB part : shape.toAabbs()) {
            AABB world = part.move(below);
            if (world.maxX > footprint.minX && world.minX < footprint.maxX
                    && world.maxZ > footprint.minZ && world.minZ < footprint.maxZ
                    && !(world.maxY <= top)) {
                top = world.maxY;
            }
        }
        return top;
    }

    /** Flammable floor and side blocks touching burning cells take shared, protection-checked block damage. */
    private void burnBlocks(ServerLevel level, ServerPlayer resolved) {
        LongSet targets = new LongOpenHashSet();
        LongIterator it = cells.iterator();
        while (it.hasNext()) {
            BlockPos cell = BlockPos.of(it.nextLong());
            targets.add(cell.below().asLong());
            for (Direction direction : HORIZONTAL) {
                long side = cell.relative(direction).asLong();
                if (!cells.contains(side)) {
                    targets.add(side);
                }
            }
        }
        LongIterator targetIt = targets.iterator();
        while (targetIt.hasNext()) {
            BlockPos pos = BlockPos.of(targetIt.nextLong());
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.ignitedByLava()) {
                BlockDamage.apply(level, resolved, pos, GrenadeTuning.FIRE_BLOCK_DAMAGE);
            }
        }
    }

    /** Ends the pool early (water or smoke) with an {@code extinguish} event. */
    public void extinguish() {
        if (level() instanceof ServerLevel level && isAlive()) {
            GrenadeEffects.sound(level, getKind(), GrenadeSoundPayload.EVENT_EXTINGUISH, position(), 1.0F, 1.0F,
                    GrenadeTuning.SOUND_RANGE_QUIET);
            level.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 0.2, getZ(), 12, getMaxRadius() * 0.4, 0.1,
                    getMaxRadius() * 0.4, 0.02);
        }
        discard();
    }

    // ---------------------------------------------------------------- Fire footprint

    /**
     * The free cell with a floor where a burst at {@code burst} lands, within the airburst allowance. Only the
     * collision boxes of the burst's block that lie in the burst's own X/Z column count: when one of them is a
     * surface at or under the burst (a bottom slab, a stair's lower tread or upper step), the fire burns on the
     * cell above that block; when the column holds only geometry above the burst (the empty lower half of a top
     * slab) the search continues downward. A burst inside solid geometry, or with geometry both under and over it
     * in its column, makes no fire. The search never skips supporting geometry and never rises above a surface.
     */
    static @Nullable BlockPos findFireOrigin(ServerLevel level, Vec3 burst) {
        BlockPos cell = BlockPos.containing(burst);
        if (!level.isLoaded(cell)) {
            return null;
        }
        VoxelShape shape = level.getBlockState(cell).getCollisionShape(level, cell);
        if (!shape.isEmpty()) {
            double lx = burst.x - cell.getX();
            double ly = burst.y - cell.getY();
            double lz = burst.z - cell.getZ();
            boolean support = false;
            boolean overhead = false;
            for (AABB part : shape.toAabbs()) {
                if (lx < part.minX || lx > part.maxX || lz < part.minZ || lz > part.maxZ) {
                    continue;
                }
                if (part.maxY <= ly + SURFACE_EPSILON) {
                    support = true;
                } else if (part.minY >= ly - SURFACE_EPSILON) {
                    overhead = true;
                } else {
                    return null;
                }
            }
            if (support && overhead) {
                return null;
            }
            cell = support ? cell.above() : cell.below();
            if (!isFree(level, cell)) {
                return null;
            }
        }
        for (int drop = 0; drop <= GrenadeTuning.FIRE_AIRBURST_FLOOR_BLOCKS; drop++) {
            if (isWater(level, cell) || hasFloor(level, cell)) {
                return cell;
            }
            BlockPos below = cell.below();
            if (!level.isLoaded(below) || !level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return null;
            }
            cell = below;
        }
        return null;
    }

    /**
     * Burning cells connected to {@code start} over walkable ground: each is free, dry, loaded and has a
     * collidable floor; neighbors are the four horizontal directions with at most one block of step (climbing
     * needs headroom, dropping needs the edge to be open), at most one block above/below {@code start}, within
     * {@link GrenadeTuning#FIRE_RADIUS} of it horizontally, at most {@link GrenadeTuning#FIRE_MAX_CELLS} cells.
     */
    static LongSet floodFill(ServerLevel level, BlockPos start) {
        LongSet result = new LongLinkedOpenHashSet();
        if (!isFireCell(level, start)) {
            return result;
        }
        double radiusSq = (double) GrenadeTuning.FIRE_RADIUS * GrenadeTuning.FIRE_RADIUS;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        result.add(start.asLong());
        while (!queue.isEmpty() && result.size() < GrenadeTuning.FIRE_MAX_CELLS) {
            BlockPos current = queue.poll();
            for (Direction direction : HORIZONTAL) {
                BlockPos flat = current.relative(direction);
                int dx = flat.getX() - start.getX();
                int dz = flat.getZ() - start.getZ();
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
                BlockPos next = null;
                if (isFireCell(level, flat)) {
                    next = flat;
                } else if (flat.getY() + 1 <= start.getY() + 1 && isFree(level, current.above())
                        && isFireCell(level, flat.above())) {
                    next = flat.above();
                } else if (flat.getY() - 1 >= start.getY() - 1 && isFree(level, flat) && isFireCell(level, flat.below())) {
                    next = flat.below();
                }
                if (next != null && result.size() < GrenadeTuning.FIRE_MAX_CELLS && result.add(next.asLong())) {
                    queue.add(next);
                }
            }
        }
        return result;
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean hasFloor(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.isLoaded(below) && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    static boolean isFireCell(ServerLevel level, BlockPos pos) {
        return isFree(level, pos) && !isWater(level, pos) && hasFloor(level, pos);
    }

    static boolean isWater(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getFluidState(pos).is(FluidTags.WATER);
    }

    // ---------------------------------------------------------------- Entity plumbing

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        GrenadeKind kind = GrenadeKind.byId(tag.getString("Kind"));
        setKind(kind == null ? GrenadeKind.SMOKE : kind);
        GrenadeKind actual = getKind();
        // Saved radius/duration may only shrink the authored bounds, never grow them.
        if (tag.contains("Radius")) {
            entityData.set(DATA_MAX_RADIUS, Mth.clamp(tag.getFloat("Radius"), 0.0F, authoredRadius(actual)));
        }
        if (tag.contains("Duration")) {
            entityData.set(DATA_DURATION, Mth.clamp(tag.getInt("Duration"), 0, authoredDuration(actual)));
        }
        entityData.set(DATA_AGE, Math.max(0, tag.getInt("Age")));
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        cells.clear();
        if (actual.isFire() && tag.contains("Cells")) {
            BlockPos origin = blockPosition();
            double radius = getMaxRadius() + 0.5;
            for (long packed : tag.getLongArray("Cells")) {
                BlockPos cell = BlockPos.of(packed);
                double dx = cell.getX() - origin.getX();
                double dz = cell.getZ() - origin.getZ();
                if (cells.size() < GrenadeTuning.FIRE_MAX_CELLS && Math.abs(cell.getY() - origin.getY()) <= 1
                        && dx * dx + dz * dz <= radius * radius) {
                    cells.add(packed);
                }
            }
            cellsInitialized = !cells.isEmpty();
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("Kind", getKind().id());
        tag.putFloat("Radius", getMaxRadius());
        tag.putInt("Age", getAgeTicks());
        tag.putInt("Duration", getDurationTicks());
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (!cells.isEmpty()) {
            tag.putLongArray("Cells", new LongArrayList(cells).toLongArray());
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }
}
