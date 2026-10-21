package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Exact convex-polyhedron helpers over half-space sets ({@link BrushPlane}: inside when {@code n.p <= d}).
 *
 * <p>{@link #EPS} controls plane deduplication and vertex enumeration. Volume does not infer facets from fuzzy
 * vertex membership: it clips a closed polygon mesh by each half-space, retaining the clipped faces and adding only
 * the new cut face. Thus nearby planes cannot each claim overlapping portions of the same face.
 *
 * <p>Vertex enumeration and every clipping, cap construction, sorting and tetrahedron operation charge the same
 * {@link Work} budget.
 */
public final class Polytope {
    /** Facet tolerance (blocks): 1e-7 blocks = 4e-6 Source units. */
    static final double EPS = 1e-7;

    private Polytope() {}

    /** Shared complexity budget for every vertex enumeration of one conversion. */
    public static final class Work {
        private final long budget;
        private long used;

        public Work(long budget) {
            this.budget = budget;
        }

        /** Charges the cost of enumerating the vertices of {@code n} planes; throws once the budget is exceeded. */
        void charge(int n) {
            charge((long) n * (n - 1) * (n - 2) / 6 * n);
        }

        private void charge(long cost) {
            if (cost < 0 || cost > budget - used) {
                throw new SurfImportException("map brushes are too complex (work budget of " + budget
                        + " work units exceeded)");
            }
            used += cost;
        }

        public long used() {
            return used;
        }
    }

    /**
     * Removes duplicate half-spaces. Planes with the same normal (within 1e-12 per component) keep only the tightest
     * (smallest distance); vbsp likewise drops duplicate planes ({@code CMapFile::LoadSideCallback}). Planes whose
     * normal components and distance all agree within {@link #EPS} are the same facet and keep the tighter one.
     */
    public static List<BrushPlane> dedupe(List<BrushPlane> planes) {
        List<BrushPlane> out = new ArrayList<>();
        outer:
        for (BrushPlane plane : planes) {
            for (int i = 0; i < out.size(); i++) {
                BrushPlane kept = out.get(i);
                if (sameNormal(kept, plane, 1e-12)
                        || (sameNormal(kept, plane, EPS) && Math.abs(kept.distance() - plane.distance()) <= EPS)) {
                    if (plane.distance() < kept.distance()) out.set(i, plane);
                    continue outer;
                }
            }
            out.add(plane);
        }
        return out;
    }

    private static boolean sameNormal(BrushPlane a, BrushPlane b, double tolerance) {
        return Math.abs(a.nx() - b.nx()) <= tolerance && Math.abs(a.ny() - b.ny()) <= tolerance
                && Math.abs(a.nz() - b.nz()) <= tolerance;
    }

    /** Every vertex of the intersection: triple-plane intersections inside all half-spaces, de-duplicated. */
    public static List<Vec3> vertices(List<BrushPlane> planes, Work work) {
        int n = planes.size();
        work.charge(n);
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    Vec3 p = intersect(planes.get(i), planes.get(j), planes.get(k));
                    if (p == null || !inside(planes, p, EPS)) continue;
                    boolean duplicate = false;
                    for (Vec3 q : out) {
                        if (q.distanceToSqr(p) <= EPS * EPS) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) out.add(p);
                }
            }
        }
        return out;
    }

    static boolean inside(List<BrushPlane> planes, Vec3 p, double eps) {
        for (BrushPlane plane : planes) if (plane.signedDistance(p.x, p.y, p.z) > eps) return false;
        return true;
    }

    /** Intersection point of three planes, or null when two are (nearly) parallel. */
    static Vec3 intersect(BrushPlane a, BrushPlane b, BrushPlane c) {
        // Cramer's rule on [a; b; c] p = [da; db; dc].
        double bcx = b.ny() * c.nz() - b.nz() * c.ny();
        double bcy = b.nz() * c.nx() - b.nx() * c.nz();
        double bcz = b.nx() * c.ny() - b.ny() * c.nx();
        double det = a.nx() * bcx + a.ny() * bcy + a.nz() * bcz;
        if (Math.abs(det) < 1e-10) return null;
        double cax = c.ny() * a.nz() - c.nz() * a.ny();
        double cay = c.nz() * a.nx() - c.nx() * a.nz();
        double caz = c.nx() * a.ny() - c.ny() * a.nx();
        double abx = a.ny() * b.nz() - a.nz() * b.ny();
        double aby = a.nz() * b.nx() - a.nx() * b.nz();
        double abz = a.nx() * b.ny() - a.ny() * b.nx();
        double x = (a.distance() * bcx + b.distance() * cax + c.distance() * abx) / det;
        double y = (a.distance() * bcy + b.distance() * cay + c.distance() * aby) / det;
        double z = (a.distance() * bcz + b.distance() * caz + c.distance() * abz) / det;
        return new Vec3(x, y, z);
    }

    /** Volume of the bounded intersection (0 when empty or flat), using a closed clipped polygon mesh. */
    public static double volume(List<BrushPlane> planes, Work work) {
        List<Vec3> vertices = vertices(planes, work);
        return volume(planes, vertices, work);
    }

    static double volume(List<BrushPlane> planes, List<Vec3> vertices, Work work) {
        work.charge(vertices.size() + 24L);
        if (vertices.size() < 4) return 0.0;
        // Enumeration accepts points within EPS of the half-spaces. Its bounds, expanded by EPS, seed a closed
        // enclosing box; clipping uses strict signed distances, not fuzzy face membership or facet deduplication.
        AABB box = bounds(vertices).inflate(EPS);
        Vec3 a = new Vec3(box.minX, box.minY, box.minZ), b = new Vec3(box.maxX, box.minY, box.minZ);
        Vec3 c = new Vec3(box.maxX, box.maxY, box.minZ), d = new Vec3(box.minX, box.maxY, box.minZ);
        Vec3 e = new Vec3(box.minX, box.minY, box.maxZ), f = new Vec3(box.maxX, box.minY, box.maxZ);
        Vec3 g = new Vec3(box.maxX, box.maxY, box.maxZ), h = new Vec3(box.minX, box.maxY, box.maxZ);
        List<List<Vec3>> faces = new ArrayList<>(List.of(List.of(a, b, c, d), List.of(e, f, g, h),
                List.of(a, b, f, e), List.of(d, c, g, h), List.of(a, d, h, e), List.of(b, c, g, f)));
        for (BrushPlane plane : planes) {
            List<List<Vec3>> clipped = new ArrayList<>();
            List<Vec3> cap = new ArrayList<>();
            for (List<Vec3> face : faces) {
                work.charge(2L * face.size());
                List<Vec3> polygon = new ArrayList<>();
                Vec3 previous = face.getLast();
                double before = plane.signedDistance(previous.x, previous.y, previous.z);
                for (Vec3 current : face) {
                    double after = plane.signedDistance(current.x, current.y, current.z);
                    if ((before <= 0) != (after <= 0)) {
                        Vec3 cut = crossing(previous, current, before, after);
                        polygon.add(cut);
                        work.charge(cap.size() + 1L);
                        if (!cap.contains(cut)) cap.add(cut);
                    }
                    if (after <= 0) polygon.add(current);
                    previous = current;
                    before = after;
                }
                if (polygon.size() >= 3) clipped.add(polygon);
            }
            if (cap.size() >= 3) {
                work.charge((long) cap.size() * (1 + (32 - Integer.numberOfLeadingZeros(cap.size()))));
                sortFace(plane, cap);
                clipped.add(cap);
            }
            faces = clipped;
            if (faces.isEmpty()) return 0.0;
        }
        Vec3 centre = Vec3.ZERO;
        int count = 0;
        for (List<Vec3> face : faces) {
            work.charge(face.size());
            for (Vec3 vertex : face) {
                centre = centre.add(vertex);
                count++;
            }
        }
        centre = centre.scale(1.0 / count); // Convex combination: inside the final clipped solid.
        double total = 0.0;
        for (List<Vec3> face : faces) {
            work.charge(face.size());
            Vec3 first = face.getFirst().subtract(centre);
            for (int i = 1; i + 1 < face.size(); i++) {
                total += Math.abs(first.dot(face.get(i).subtract(centre)
                        .cross(face.get(i + 1).subtract(centre)))) / 6.0;
            }
        }
        return total;
    }

    /** Canonical edge direction makes both incident faces compute bit-identical cut vertices. */
    private static Vec3 crossing(Vec3 a, Vec3 b, double da, double db) {
        if (da == 0) return a;
        if (db == 0) return b;
        if (a.x > b.x || (a.x == b.x && (a.y > b.y || (a.y == b.y && a.z > b.z)))) {
            return crossing(b, a, db, da);
        }
        double t = da / (da - db);
        return new Vec3(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z));
    }

    /** Orders the single new cut face around its normal; existing clipped faces retain their topology. */
    private static void sortFace(BrushPlane plane, List<Vec3> face) {
        Vec3 normal = new Vec3(plane.nx(), plane.ny(), plane.nz());
        Vec3 helper = Math.abs(normal.x) < 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = normal.cross(helper).normalize();
        Vec3 w = normal.cross(u);
        Vec3 c = Vec3.ZERO;
        for (Vec3 v : face) c = c.add(v);
        Vec3 centre = c.scale(1.0 / face.size());
        face.sort(Comparator.comparingDouble(v -> {
            double x = v.x - centre.x, y = v.y - centre.y, z = v.z - centre.z;
            return Math.atan2(x * w.x + y * w.y + z * w.z, x * u.x + y * u.y + z * u.z);
        }));
    }

    public static AABB bounds(List<Vec3> vertices) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** True when the plane's normal is exactly one of the six axis directions. */
    public static boolean isAxial(BrushPlane plane) {
        int ones = 0;
        int zeros = 0;
        for (double c : new double[] {plane.nx(), plane.ny(), plane.nz()}) {
            if (c == 0.0) zeros++;
            else if (Math.abs(c) == 1.0) ones++;
        }
        return ones == 1 && zeros == 2;
    }

    /** The six faces of the unit cell at {@code (x, y, z)}. */
    static List<BrushPlane> cell(int x, int y, int z) {
        return List.of(new BrushPlane(1, 0, 0, x + 1), new BrushPlane(-1, 0, 0, -x),
                new BrushPlane(0, 1, 0, y + 1), new BrushPlane(0, -1, 0, -y),
                new BrushPlane(0, 0, 1, z + 1), new BrushPlane(0, 0, -1, -z));
    }
}
