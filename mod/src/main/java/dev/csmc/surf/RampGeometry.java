package dev.csmc.surf;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Everything derived from one canonical set of block-local surf ramp planes: the exact solid (unit cell ∩
 * half-spaces), its render polygons, the inscribed vanilla collision staircase and the circumscribed outline shape.
 * Instances are immutable and interned per plane list, so every ramp cell with the same local planes shares them.
 */
public final class RampGeometry {
    /** Horizontal resolution of the vanilla staircase approximations: 1/8 block columns. */
    public static final int COLUMNS = 8;
    /** Vertical quantisation of staircase box tops/bottoms: 1/64 block, rounded towards the inside (collision). */
    public static final int Y_STEPS = 64;
    /** Smallest solid volume (blocks³) a ramp cell may have; thinner slivers are rejected as empty. */
    public static final double MIN_VOLUME = 1.0E-6;

    private static final int CACHE_LIMIT = 4096;
    private static final Map<List<BrushPlane>, RampGeometry> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<BrushPlane>, RampGeometry> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    /** One render polygon in block-local coordinates, counter-clockwise around {@code normal}. */
    public record Polygon(List<float[]> vertices, float nx, float ny, float nz, Direction cullFace,
            Direction nominalFace) {
        /** True when the normal is a coordinate axis (a cell face or an axis-aligned cut). */
        public boolean isAxisAligned() {
            int zeros = (nx == 0 ? 1 : 0) + (ny == 0 ? 1 : 0) + (nz == 0 ? 1 : 0);
            return zeros == 2;
        }
    }

    private final List<BrushPlane> planes;
    private final double volume;
    private final AABB bounds;
    private final boolean fullCell;
    private final List<Polygon> polygons;
    private final VoxelShape collision;
    private final VoxelShape outline;

    private RampGeometry(List<BrushPlane> planes) {
        this.planes = List.copyOf(planes);
        ConvexCell solid = solid(this.planes);
        this.volume = solid.isEmpty() ? 0 : solid.volume();
        if (solid.isEmpty() || volume < MIN_VOLUME) {
            this.bounds = new AABB(0, 0, 0, 0, 0, 0);
            this.fullCell = false;
            this.polygons = List.of();
            this.collision = Shapes.empty();
            this.outline = Shapes.empty();
            return;
        }
        double[] b = solid.bounds();
        this.bounds = new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
        this.fullCell = volume >= 1.0 - 1.0E-9;
        this.polygons = polygons(solid);
        this.collision = inscribedStaircase(this.planes);
        this.outline = circumscribedStaircase(solid);
    }

    /** Interned geometry for already canonical, deduplicated local planes. */
    static RampGeometry of(List<BrushPlane> canonicalPlanes) {
        List<BrushPlane> key = List.copyOf(canonicalPlanes);
        RampGeometry cached = CACHE.get(key);
        if (cached != null) return cached;
        RampGeometry created = new RampGeometry(key);
        CACHE.put(key, created);
        return created;
    }

    static ConvexCell solid(List<BrushPlane> planes) {
        ConvexCell cell = ConvexCell.unitCell();
        for (BrushPlane plane : planes) cell = cell.clip(plane);
        return cell;
    }

    /** Canonical block-local planes; the solid is the unit cell intersected with their half-spaces. */
    public List<BrushPlane> planes() {
        return planes;
    }

    /** Solid volume in blocks³; below {@link #MIN_VOLUME} the cell counts as empty. */
    public double volume() {
        return volume;
    }

    public boolean isEmpty() {
        return volume < MIN_VOLUME;
    }

    /** True when no plane removes anything from the unit cell. */
    public boolean isFullCell() {
        return fullCell;
    }

    /** Tight block-local bounds of the solid. */
    public AABB bounds() {
        return bounds;
    }

    /** Exact boundary polygons of the solid (block-local). */
    public List<Polygon> polygons() {
        return polygons;
    }

    /** Union of 1/8-block columns, each box entirely inside the solid; never above the true surface. */
    public VoxelShape inscribedCollision() {
        return collision;
    }

    /** Union of 1/8-block columns covering the solid; used for outline, selection and ray picking. */
    public VoxelShape outline() {
        return outline;
    }

    private static List<Polygon> polygons(ConvexCell solid) {
        List<Polygon> out = new ArrayList<>();
        for (ConvexCell.Face face : solid.faces()) {
            Direction cull = face.cellFace() != null && ConvexCell.area(face) >= 1.0 - 1.0E-9 ? face.cellFace() : null;
            double[] n = face.normal();
            Direction nominal = face.cellFace() != null ? face.cellFace()
                    : Direction.getNearest((float) n[0], (float) n[1], (float) n[2]);
            List<float[]> vertices = new ArrayList<>(face.vertices().size());
            for (double[] v : face.vertices()) vertices.add(new float[] {(float) v[0], (float) v[1], (float) v[2]});
            out.add(new Polygon(List.copyOf(vertices), (float) n[0], (float) n[1], (float) n[2], cull, nominal));
        }
        return List.copyOf(out);
    }

    /**
     * For each 1/8 column the largest y interval whose box lies inside every half-space and the cell. The planes are
     * linear, so checking the four column corners bounds the whole column footprint. Tops are rounded down and bottoms
     * up to {@link #Y_STEPS}, so every box is strictly inside the exact solid.
     */
    private static VoxelShape inscribedStaircase(List<BrushPlane> planes) {
        List<VoxelShape> boxes = new ArrayList<>(COLUMNS * COLUMNS);
        for (int i = 0; i < COLUMNS; i++) {
            for (int k = 0; k < COLUMNS; k++) {
                double x0 = (double) i / COLUMNS, x1 = (double) (i + 1) / COLUMNS;
                double z0 = (double) k / COLUMNS, z1 = (double) (k + 1) / COLUMNS;
                double lo = 0, hi = 1;
                boolean empty = false;
                for (BrushPlane p : planes) {
                    for (int c = 0; c < 4 && !empty; c++) {
                        double x = (c & 1) == 0 ? x0 : x1;
                        double z = (c & 2) == 0 ? z0 : z1;
                        double rest = p.distance() - p.nx() * x - p.nz() * z;
                        if (p.ny() > 0) hi = Math.min(hi, rest / p.ny());
                        else if (p.ny() < 0) lo = Math.max(lo, rest / p.ny());
                        else if (rest < 0) empty = true;
                    }
                }
                if (empty) continue;
                double top = Math.floor((hi - ConvexCell.EPSILON) * Y_STEPS) / Y_STEPS;
                double bottom = Math.ceil((lo + ConvexCell.EPSILON) * Y_STEPS) / Y_STEPS;
                top = Math.min(top, 1.0);
                bottom = Math.max(bottom, 0.0);
                if (top > bottom) boxes.add(Shapes.box(x0, bottom, z0, x1, top, z1));
            }
        }
        return union(boxes);
    }

    /** For each 1/8 column the y extent of the solid within it, rounded outwards to {@link #Y_STEPS}. */
    private static VoxelShape circumscribedStaircase(ConvexCell solid) {
        List<VoxelShape> boxes = new ArrayList<>(COLUMNS * COLUMNS);
        for (int i = 0; i < COLUMNS; i++) {
            for (int k = 0; k < COLUMNS; k++) {
                double x0 = (double) i / COLUMNS, x1 = (double) (i + 1) / COLUMNS;
                double z0 = (double) k / COLUMNS, z1 = (double) (k + 1) / COLUMNS;
                ConvexCell column = solid.clip(new BrushPlane(1, 0, 0, x1)).clip(new BrushPlane(-1, 0, 0, -x0))
                        .clip(new BrushPlane(0, 0, 1, z1)).clip(new BrushPlane(0, 0, -1, -z0));
                if (column.isEmpty()) continue;
                double[] b = column.bounds();
                double bottom = Math.max(0.0, Math.floor((b[1] + ConvexCell.EPSILON) * Y_STEPS) / Y_STEPS);
                double top = Math.min(1.0, Math.ceil((b[4] - ConvexCell.EPSILON) * Y_STEPS) / Y_STEPS);
                if (top > bottom) boxes.add(Shapes.box(x0, bottom, z0, x1, top, z1));
            }
        }
        return union(boxes);
    }

    private static VoxelShape union(List<VoxelShape> boxes) {
        if (boxes.isEmpty()) return Shapes.empty();
        VoxelShape shape = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) shape = Shapes.joinUnoptimized(shape, boxes.get(i),
                net.minecraft.world.phys.shapes.BooleanOp.OR);
        return shape.optimize();
    }
}
