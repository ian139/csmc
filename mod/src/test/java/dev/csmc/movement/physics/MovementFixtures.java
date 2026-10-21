package dev.csmc.movement.physics;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;

/** Geometry builders (block coordinates) and a Minecraft-hull player for the movement tests. */
final class MovementFixtures {
    /** Minecraft player hull in units: 0.6 x 1.8 blocks; step height 0.6 blocks (contract hull/step mapping). */
    static final double HALF_WIDTH = 0.3 * CsUnits.UNITS_PER_BLOCK;
    static final double HEIGHT = 1.8 * CsUnits.UNITS_PER_BLOCK;
    static final double STEP = 0.6 * CsUnits.UNITS_PER_BLOCK;
    static final double KNIFE_SPEED = 250.0;

    private MovementFixtures() {}

    static double dt(CsMovementParams params) {
        return 1.0 / (CsUnits.TICKS_PER_SECOND * params.substepsPerTick());
    }

    static CsMoveInput input(double forward, double side, boolean jump, double yaw) {
        return new CsMoveInput(forward, side, jump, false, false, yaw, KNIFE_SPEED, HALF_WIDTH, HEIGHT, STEP, 1.0);
    }

    static CsMoveInput input(double forward, double side, boolean jump, boolean ducked, boolean walk, double yaw) {
        return new CsMoveInput(forward, side, jump, ducked, walk, yaw, KNIFE_SPEED, HALF_WIDTH, HEIGHT, STEP, 1.0);
    }

    static ConvexBrush box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return ConvexBrush.box(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** A large flat floor whose top is y = 0. */
    static ConvexBrush floor() {
        return box(-1000, -1, -1000, 1000, 0, 1000);
    }

    /** Player standing on the y = 0 floor at the origin (feet flush with the floor, as Minecraft places them). */
    static CsPlayerState standing() {
        CsPlayerState state = new CsPlayerState();
        state.grounded = true;
        return state;
    }

    /** Yaw (Minecraft degrees) that faces the horizontal direction (x, z). */
    static double yawFacing(double x, double z) {
        return Math.toDegrees(Math.atan2(-x, z));
    }

    /**
     * A sloped prism frame: local slope rising toward local +x with normal {@code (-sin a, cos a, 0)} (a = slope
     * angle from horizontal), foot line on local x = 0, y = 0, running along local z; then yawed by {@code yawDeg}
     * about +y and translated by {@code (tx, ty, tz)} blocks.
     */
    static final class Frame {
        final double cosYaw;
        final double sinYaw;
        final double tx;
        final double ty;
        final double tz;

        Frame(double yawDeg, double tx, double ty, double tz) {
            double r = Math.toRadians(yawDeg);
            this.cosYaw = Math.cos(r);
            this.sinYaw = Math.sin(r);
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
        }

        /** Local direction to world. */
        double[] dir(double x, double y, double z) {
            return new double[] {x * cosYaw + z * sinYaw, y, -x * sinYaw + z * cosYaw};
        }

        /** Local point (blocks) to world (blocks). */
        double[] point(double x, double y, double z) {
            double[] d = dir(x, y, z);
            return new double[] {d[0] + tx, d[1] + ty, d[2] + tz};
        }

        /** Local plane {@code n.p <= d} (blocks) to world. */
        BrushPlane plane(double nx, double ny, double nz, double d) {
            double[] n = dir(nx, ny, nz);
            return BrushPlane.of(n[0], n[1], n[2], d + n[0] * tx + n[1] * ty + n[2] * tz);
        }
    }

    /**
     * One-sided surf ramp in local coordinates: slope {@code -sin(a) x + cos(a) y <= 0}, vertical back at
     * {@code x = height / tan(a)} (the ridge), floor {@code y >= 0}, ends {@code z in [z0, z1]}.
     */
    static List<BrushPlane> rampPlanes(Frame frame, double slopeDeg, double height, double z0, double z1) {
        double a = Math.toRadians(slopeDeg);
        double ridgeX = height / Math.tan(a);
        List<BrushPlane> planes = new ArrayList<>();
        planes.add(frame.plane(-Math.sin(a), Math.cos(a), 0, 0));
        planes.add(frame.plane(1, 0, 0, ridgeX));
        planes.add(frame.plane(0, -1, 0, 0));
        planes.add(frame.plane(0, 0, 1, z1));
        planes.add(frame.plane(0, 0, -1, -z0));
        return planes;
    }

    static ConvexBrush brush(List<BrushPlane> planes, double pad) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (double[] v : vertices(planes)) {
            minX = Math.min(minX, v[0]);
            minY = Math.min(minY, v[1]);
            minZ = Math.min(minZ, v[2]);
            maxX = Math.max(maxX, v[0]);
            maxY = Math.max(maxY, v[1]);
            maxZ = Math.max(maxZ, v[2]);
        }
        return new ConvexBrush(planes, new AABB(minX - pad, minY - pad, minZ - pad, maxX + pad, maxY + pad, maxZ + pad));
    }

    /**
     * The same solid split into Minecraft block cells: every block cell the solid's surface passes through becomes
     * {@code cell ∩ solid}, every cell fully inside becomes a full block box (like a ramp built from blocks).
     */
    static List<ConvexBrush> splitIntoCells(List<BrushPlane> planes) {
        ConvexBrush whole = brush(planes, 0);
        AABB b = whole.bounds();
        List<ConvexBrush> cells = new ArrayList<>();
        for (int x = (int) Math.floor(b.minX); x < Math.ceil(b.maxX); x++) {
            for (int y = (int) Math.floor(b.minY); y < Math.ceil(b.maxY); y++) {
                for (int z = (int) Math.floor(b.minZ); z < Math.ceil(b.maxZ); z++) {
                    boolean allInside = true;
                    boolean anyInside = false;
                    for (int c = 0; c < 8; c++) {
                        double px = x + (c & 1), py = y + ((c >> 1) & 1), pz = z + ((c >> 2) & 1);
                        boolean in = true;
                        for (BrushPlane p : planes) {
                            if (p.signedDistance(px, py, pz) > 1e-9) {
                                in = false;
                                break;
                            }
                        }
                        allInside &= in;
                        anyInside |= in;
                    }
                    AABB cell = new AABB(x, y, z, x + 1, y + 1, z + 1);
                    if (allInside) {
                        cells.add(ConvexBrush.box(cell));
                        continue;
                    }
                    List<BrushPlane> cellPlanes = new ArrayList<>(ConvexBrush.box(cell).planes());
                    cellPlanes.addAll(planes);
                    if (vertices(cellPlanes).size() >= 4 || anyInside) {
                        cells.add(new ConvexBrush(cellPlanes, cell));
                    }
                }
            }
        }
        return cells;
    }

    /** Brute-force vertices of the intersection of half-spaces (independent of ConvexBrushWorld). */
    static List<double[]> vertices(List<BrushPlane> planes) {
        List<double[]> out = new ArrayList<>();
        int n = planes.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    double[] p = intersect(planes.get(i), planes.get(j), planes.get(k));
                    if (p == null) continue;
                    boolean in = true;
                    for (BrushPlane q : planes) {
                        if (q.signedDistance(p[0], p[1], p[2]) > 1e-9 * (1 + Math.abs(q.distance()))) {
                            in = false;
                            break;
                        }
                    }
                    if (in) out.add(p);
                }
            }
        }
        return out;
    }

    private static double[] intersect(BrushPlane a, BrushPlane b, BrushPlane c) {
        double det = a.nx() * (b.ny() * c.nz() - b.nz() * c.ny()) - a.ny() * (b.nx() * c.nz() - b.nz() * c.nx())
                + a.nz() * (b.nx() * c.ny() - b.ny() * c.nx());
        if (Math.abs(det) < 1e-12) return null;
        double x = (a.distance() * (b.ny() * c.nz() - b.nz() * c.ny())
                - a.ny() * (b.distance() * c.nz() - b.nz() * c.distance())
                + a.nz() * (b.distance() * c.ny() - b.ny() * c.distance())) / det;
        double y = (a.nx() * (b.distance() * c.nz() - b.nz() * c.distance())
                - a.distance() * (b.nx() * c.nz() - b.nz() * c.nx())
                + a.nz() * (b.nx() * c.distance() - b.distance() * c.nx())) / det;
        double z = (a.nx() * (b.ny() * c.distance() - b.distance() * c.ny())
                - a.ny() * (b.nx() * c.distance() - b.distance() * c.nx())
                + a.distance() * (b.nx() * c.ny() - b.ny() * c.nx())) / det;
        return new double[] {x, y, z};
    }

    /**
     * Whether the player box (origin bottom centre, units) grown by {@code grow} units on every side overlaps the
     * brush, by brute-force vertex enumeration of box ∩ brush in units. Independent of the Minkowski sweep.
     */
    static boolean boxOverlaps(List<BrushPlane> brushBlocks, double ox, double oy, double oz, double halfWidth,
            double height, double grow) {
        List<BrushPlane> planes = new ArrayList<>();
        double u = CsUnits.UNITS_PER_BLOCK;
        for (BrushPlane p : brushBlocks) {
            planes.add(new BrushPlane(p.nx(), p.ny(), p.nz(), p.distance() * u));
        }
        planes.add(new BrushPlane(1, 0, 0, ox + halfWidth + grow));
        planes.add(new BrushPlane(-1, 0, 0, -(ox - halfWidth - grow)));
        planes.add(new BrushPlane(0, 1, 0, oy + height + grow));
        planes.add(new BrushPlane(0, -1, 0, -(oy - grow)));
        planes.add(new BrushPlane(0, 0, 1, oz + halfWidth + grow));
        planes.add(new BrushPlane(0, 0, -1, -(oz - halfWidth - grow)));
        return !vertices(planes).isEmpty();
    }

    /**
     * Origin (units) of a player whose hull rests {@code gap} units above the slope plane (block plane), at local
     * slope height {@code y} blocks and local axis position {@code z} blocks.
     */
    static double[] originOnSlope(Frame frame, double slopeDeg, double yBlocks, double zBlocks, double gap) {
        double a = Math.toRadians(slopeDeg);
        // On the slope -sin(a) x + cos(a) y = 0, so x = y / tan(a).
        double[] q = frame.point(yBlocks / Math.tan(a), yBlocks, zBlocks);
        double[] n = frame.dir(-Math.sin(a), Math.cos(a), 0);
        double u = CsUnits.UNITS_PER_BLOCK;
        // The hull corner nearest the plane is the bottom one, HALF_WIDTH * (|nx| + |nz|) below the origin along n.
        double t = gap + HALF_WIDTH * (Math.abs(n[0]) + Math.abs(n[2]));
        return new double[] {q[0] * u + n[0] * t, q[1] * u + n[1] * t, q[2] * u + n[2] * t};
    }
}
