package dev.csmc.surf;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Public surf ramp API (shared contract with the map importer).
 *
 * <p>A ramp cell stores 1..4 block-local planes; its solid is the unit cell {@code [0,1]^3} intersected with every
 * half-space {@code n·p <= d}. Planes are canonicalised onto a {@link #GRID} grid (normal components and distance), so
 * a canonical world plane translated by an integer block position is exact in double arithmetic: every cell cut from
 * the same canonical world plane reproduces bit-identical world coefficients (seam-free collision). Positions up to
 * 2^25 blocks from the origin keep the products {@code n_i * pos_i} within 49 significant bits.
 */
public final class SurfRamps {
    /** Canonical coefficient grid: 2^-24. */
    public static final double GRID = 0x1.0p-24;
    public static final int MAX_PLANES = 4;

    private SurfRamps() {}

    /**
     * Places or updates a surf ramp at {@code pos} with block-local planes. The planes are canonicalised and exact
     * duplicates removed; the call fails (returns {@code false}, world unchanged) when the result has no plane or
     * more than {@link #MAX_PLANES}, when the solid inside the cell is empty (volume below
     * {@link RampGeometry#MIN_VOLUME}), or when the cell already holds a ramp with exactly these planes.
     */
    public static boolean place(ServerLevel level, BlockPos pos, List<BrushPlane> localPlanes) {
        return place(level, pos, localPlanes, Block.UPDATE_ALL);
    }

    private static boolean place(ServerLevel level, BlockPos pos, List<BrushPlane> localPlanes, int updateFlags) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        RampGeometry geometry = validate(localPlanes);
        if (geometry == null) return false;
        BlockState state = level.getBlockState(pos);
        if (state.is(Surf.SURF_RAMP) && level.getBlockEntity(pos) instanceof SurfRampBlockEntity ramp) {
            if (ramp.geometry().planes().equals(geometry.planes())) return false;
            ramp.setGeometry(geometry, updateFlags);
            return true;
        }
        if (!level.setBlock(pos, Surf.SURF_RAMP.defaultBlockState(), updateFlags)) return false;
        if (!(level.getBlockEntity(pos) instanceof SurfRampBlockEntity ramp)) return false;
        ramp.setGeometry(geometry, updateFlags);
        return true;
    }

    /**
     * {@link #place} for world-space planes: each plane is canonicalised in world space first, then translated to
     * the cell. Cells placed from the same world plane get bit-identical world coefficients back from
     * {@link #worldPlanes}. Recommended entry point for builders and importers.
     */
    public static boolean placeWorld(ServerLevel level, BlockPos pos, List<BrushPlane> worldPlanes) {
        return place(level, pos, toLocal(worldPlanes, pos));
    }

    /**
     * {@link #placeWorld} with explicit block update flags, applied both to block placement and geometry updates.
     * Importers that have prepared only the target chunk can use
     * {@code Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE} to sync clients without neighbor update cascades
     * (which could otherwise synchronously load adjacent chunks). The caller must already have loaded the target.
     */
    public static boolean placeWorld(ServerLevel level, BlockPos pos, List<BrushPlane> worldPlanes, int updateFlags) {
        return place(level, pos, toLocal(worldPlanes, pos), updateFlags);
    }

    /** Block-local planes of the ramp at {@code pos}, or an empty list when it is not a surf ramp. */
    public static List<BrushPlane> localPlanes(BlockGetter level, BlockPos pos) {
        RampGeometry geometry = geometry(level, pos);
        return geometry == null ? List.of() : geometry.planes();
    }

    /** World-space planes of the ramp at {@code pos} (exact translation), or an empty list. */
    public static List<BrushPlane> worldPlanes(BlockGetter level, BlockPos pos) {
        List<BrushPlane> local = localPlanes(level, pos);
        List<BrushPlane> world = new ArrayList<>(local.size());
        for (BrushPlane plane : local) world.add(toWorld(plane, pos));
        return List.copyOf(world);
    }

    /**
     * Geometry of the surf ramp at {@code pos}; a ramp state whose block entity is not (yet) available uses the
     * default geometry. {@code null} when the block is not a surf ramp.
     */
    public static RampGeometry geometry(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Surf.SURF_RAMP)) return null;
        return level.getBlockEntity(pos) instanceof SurfRampBlockEntity ramp ? ramp.geometry() : defaultGeometry();
    }

    /**
     * Canonical, deduplicated geometry for {@code localPlanes}, or {@code null} when they do not describe a valid
     * ramp cell (no plane, more than {@link #MAX_PLANES} distinct planes, or an empty solid).
     */
    public static RampGeometry validate(List<BrushPlane> localPlanes) {
        if (localPlanes == null || localPlanes.isEmpty()) return null;
        // At most MAX_PLANES distinct planes are ever held, so each contains() is O(1); the scan stops as soon as a
        // fifth distinct canonical plane appears (oversized input costs at most one pass up to that plane).
        List<BrushPlane> canonical = new ArrayList<>(MAX_PLANES);
        for (BrushPlane plane : localPlanes) {
            BrushPlane c = canonicalPlane(Objects.requireNonNull(plane, "plane"));
            if (canonical.contains(c)) continue;
            if (canonical.size() == MAX_PLANES) return null;
            canonical.add(c);
        }
        RampGeometry geometry = RampGeometry.of(canonical);
        return geometry.isEmpty() ? null : geometry;
    }

    /**
     * {@code plane} with normal components and distance rounded to the {@link #GRID} grid. Idempotent: an
     * already-canonical plane is returned with bit-identical coefficients (dividing and multiplying by the power of
     * two {@link #GRID} is exact, and {@link Math#rint} of an integer is that integer).
     */
    public static BrushPlane canonicalPlane(BrushPlane plane) {
        return new BrushPlane(round(plane.nx()), round(plane.ny()), round(plane.nz()), round(plane.distance()));
    }

    /**
     * {@code plane} canonicalised, then moved by the integer block offset {@code (dx, dy, dz)}. The canonical normal
     * is kept bit-identical; the new distance {@code d + n·offset} is computed exactly (every term is a multiple of
     * {@link #GRID}; for offsets up to 2^25 blocks and {@code |d| < 2^27} the sum needs at most 53 significant bits)
     * and then snapped to the grid, which is a no-op whenever the sum is exact.
     *
     * <p>Use this to place map-local geometry: canonicalise in map-local space (before deciding which cells are
     * cut), then translate the canonical plane to the import origin with this helper. The world plane is then
     * independent of the origin up to the exact translation, and {@link #toLocal}/{@link #placeWorld} reproduce the
     * same block-local planes at any origin.
     */
    public static BrushPlane translateCanonical(BrushPlane plane, int dx, int dy, int dz) {
        BrushPlane c = canonicalPlane(plane);
        return new BrushPlane(c.nx(), c.ny(), c.nz(),
                round(c.distance() + c.nx() * dx + c.ny() * dy + c.nz() * dz));
    }

    /** Local plane moved to world space at {@code pos}; exact for canonical planes. */
    public static BrushPlane toWorld(BrushPlane local, BlockPos pos) {
        return new BrushPlane(local.nx(), local.ny(), local.nz(), local.distance() + local.nx() * pos.getX()
                + local.ny() * pos.getY() + local.nz() * pos.getZ() + 0.0);
    }

    /** Canonicalised world planes moved into the cell at {@code pos} ({@link #translateCanonical} by {@code -pos}). */
    public static List<BrushPlane> toLocal(List<BrushPlane> worldPlanes, BlockPos pos) {
        List<BrushPlane> local = new ArrayList<>(worldPlanes.size());
        for (BrushPlane world : worldPlanes) {
            local.add(translateCanonical(world, -pos.getX(), -pos.getY(), -pos.getZ()));
        }
        return local;
    }

    /**
     * Default cell: one plane rising towards north at rise:run 2:1 (63.43°) from the cell's south bottom edge. Source
     * treats a surface as ground only while its normal's up component is at least 0.7 (Source SDK 2013
     * {@code gamemovement.cpp}, {@code CategorizePosition}), i.e. slopes steeper than about 45.57° are surfable; 2:1
     * is comfortably surfable and continues into whole cells (see {@link #slopePlane}).
     */
    public static RampGeometry defaultGeometry() {
        return DefaultHolder.DEFAULT;
    }

    /**
     * World plane of a slope rising towards the horizontal unit direction {@code (fx, fz)} at {@code riseOverRun},
     * passing through height {@code baseY} along the line where {@code fx*x + fz*z == baseT}. Solid lies below.
     */
    public static BrushPlane slopePlane(double fx, double fz, double riseOverRun, double baseT, double baseY) {
        // y - k*(f·p) <= baseY - k*baseT
        return canonicalPlane(BrushPlane.of(-riseOverRun * fx, 1.0, -riseOverRun * fz,
                baseY - riseOverRun * baseT));
    }

    private static double round(double value) {
        return Math.rint(value / GRID) * GRID + 0.0;
    }

    private static final class DefaultHolder {
        // Rising north (-z) from the south edge z = 1 at the cell bottom: t = 1 - z.
        static final RampGeometry DEFAULT = validate(List.of(slopePlane(0, -1, 2.0, -1.0, 0.0)));
    }
}
