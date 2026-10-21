package dev.csmc.surf;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.Direction;

/**
 * Exact convex polyhedron obtained by clipping an axis-aligned box with half-spaces {@code n·p <= d}. Faces are
 * convex polygons wound counter-clockwise around their outward normal (right-hand rule, the winding Minecraft uses
 * for front faces). Immutable; {@link #clip} returns a new instance.
 */
final class ConvexCell {
    /** Vertex classification tolerance in blocks: points within it of a plane count as on the plane. */
    static final double EPSILON = 1.0E-9;

    /**
     * One face. {@code cellFace} is the box side this face lies on, or {@code null} for a face cut by a plane.
     * {@code normal} is the outward unit normal.
     */
    record Face(List<double[]> vertices, double[] normal, Direction cellFace) {}

    private final List<Face> faces;

    private ConvexCell(List<Face> faces) {
        this.faces = List.copyOf(faces);
    }

    /** The box {@code [x0,x1] x [y0,y1] x [z0,z1]}. */
    static ConvexCell box(double x0, double y0, double z0, double x1, double y1, double z1) {
        List<Face> faces = new ArrayList<>(6);
        faces.add(face(Direction.DOWN, new double[] {x0, y0, z0}, new double[] {x1, y0, z0},
                new double[] {x1, y0, z1}, new double[] {x0, y0, z1}));
        faces.add(face(Direction.UP, new double[] {x0, y1, z0}, new double[] {x0, y1, z1},
                new double[] {x1, y1, z1}, new double[] {x1, y1, z0}));
        faces.add(face(Direction.NORTH, new double[] {x0, y0, z0}, new double[] {x0, y1, z0},
                new double[] {x1, y1, z0}, new double[] {x1, y0, z0}));
        faces.add(face(Direction.SOUTH, new double[] {x0, y0, z1}, new double[] {x1, y0, z1},
                new double[] {x1, y1, z1}, new double[] {x0, y1, z1}));
        faces.add(face(Direction.WEST, new double[] {x0, y0, z0}, new double[] {x0, y0, z1},
                new double[] {x0, y1, z1}, new double[] {x0, y1, z0}));
        faces.add(face(Direction.EAST, new double[] {x1, y0, z0}, new double[] {x1, y1, z0},
                new double[] {x1, y1, z1}, new double[] {x1, y0, z1}));
        return new ConvexCell(faces);
    }

    /** The unit cell {@code [0,1]^3}. */
    static ConvexCell unitCell() {
        return box(0, 0, 0, 1, 1, 1);
    }

    private static Face face(Direction direction, double[]... vertices) {
        return new Face(List.of(vertices), new double[] {direction.getStepX(), direction.getStepY(),
                direction.getStepZ()}, direction);
    }

    List<Face> faces() {
        return faces;
    }

    boolean isEmpty() {
        return faces.size() < 4;
    }

    /**
     * Intersection with the half-space {@code plane}. A plane that leaves every vertex inside or on it returns this
     * instance unchanged (no zero-area cap face is added).
     */
    ConvexCell clip(BrushPlane plane) {
        if (isEmpty()) return this;
        boolean anyOutside = false;
        boolean anyInside = false;
        for (Face face : faces) {
            for (double[] v : face.vertices) {
                double s = plane.signedDistance(v[0], v[1], v[2]);
                if (s > EPSILON) anyOutside = true;
                else if (s < -EPSILON) anyInside = true;
            }
        }
        if (!anyOutside) return this;
        if (!anyInside) return new ConvexCell(List.of());
        List<Face> result = new ArrayList<>(faces.size() + 1);
        List<double[]> cap = new ArrayList<>();
        for (Face face : faces) {
            List<double[]> clipped = clipPolygon(face.vertices, plane, cap);
            if (clipped.size() >= 3) result.add(new Face(List.copyOf(clipped), face.normal, face.cellFace));
        }
        List<double[]> capPolygon = orderAround(dedupe(cap), plane);
        if (capPolygon.size() >= 3) {
            result.add(new Face(List.copyOf(capPolygon), new double[] {plane.nx(), plane.ny(), plane.nz()}, null));
        }
        return new ConvexCell(result);
    }

    private static List<double[]> clipPolygon(List<double[]> polygon, BrushPlane plane, List<double[]> cap) {
        List<double[]> out = new ArrayList<>(polygon.size() + 1);
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            double[] a = polygon.get(i);
            double[] b = polygon.get((i + 1) % n);
            double sa = classify(plane.signedDistance(a[0], a[1], a[2]));
            double sb = classify(plane.signedDistance(b[0], b[1], b[2]));
            if (sa <= 0) {
                out.add(a);
                if (sa == 0) cap.add(a);
            }
            if ((sa < 0 && sb > 0) || (sa > 0 && sb < 0)) {
                double da = plane.signedDistance(a[0], a[1], a[2]);
                double db = plane.signedDistance(b[0], b[1], b[2]);
                double t = da / (da - db);
                double[] p = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
                out.add(p);
                cap.add(p);
            }
        }
        return dedupe(out);
    }

    private static double classify(double signedDistance) {
        if (signedDistance > EPSILON) return 1;
        if (signedDistance < -EPSILON) return -1;
        return 0;
    }

    /** Removes consecutive and repeated points closer than {@link #EPSILON}, keeping order. */
    private static List<double[]> dedupe(List<double[]> points) {
        List<double[]> out = new ArrayList<>(points.size());
        for (double[] p : points) {
            boolean seen = false;
            for (double[] q : out) {
                if (Math.abs(p[0] - q[0]) <= EPSILON && Math.abs(p[1] - q[1]) <= EPSILON
                        && Math.abs(p[2] - q[2]) <= EPSILON) {
                    seen = true;
                    break;
                }
            }
            if (!seen) out.add(p);
        }
        return out;
    }

    /** Orders coplanar points counter-clockwise around {@code plane}'s normal. */
    private static List<double[]> orderAround(List<double[]> points, BrushPlane plane) {
        if (points.size() < 3) return points;
        double[] n = {plane.nx(), plane.ny(), plane.nz()};
        double[] helper = Math.abs(n[0]) < 0.9 ? new double[] {1, 0, 0} : new double[] {0, 1, 0};
        double[] u = normalize(cross(helper, n));
        double[] v = cross(n, u);
        double cx = 0, cy = 0, cz = 0;
        for (double[] p : points) {
            cx += p[0];
            cy += p[1];
            cz += p[2];
        }
        double[] c = {cx / points.size(), cy / points.size(), cz / points.size()};
        List<double[]> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(p -> {
            double[] r = {p[0] - c[0], p[1] - c[1], p[2] - c[2]};
            return Math.atan2(dot(r, v), dot(r, u));
        }));
        return sorted;
    }

    /** Volume by the divergence theorem over the faces. */
    double volume() {
        double total = 0;
        for (Face face : faces) {
            double area = area(face);
            double[] p = face.vertices.get(0);
            total += dot(face.normal, p) * area;
        }
        return total / 3.0;
    }

    static double area(Face face) {
        List<double[]> vs = face.vertices;
        double[] a = vs.get(0);
        double sx = 0, sy = 0, sz = 0;
        for (int i = 1; i + 1 < vs.size(); i++) {
            double[] c = cross(sub(vs.get(i), a), sub(vs.get(i + 1), a));
            sx += c[0];
            sy += c[1];
            sz += c[2];
        }
        return 0.5 * (sx * face.normal[0] + sy * face.normal[1] + sz * face.normal[2]);
    }

    /** {@code {minX, minY, minZ, maxX, maxY, maxZ}} of all vertices; only meaningful when non-empty. */
    double[] bounds() {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Face face : faces) {
            for (double[] v : face.vertices) {
                for (int i = 0; i < 3; i++) {
                    b[i] = Math.min(b[i], v[i]);
                    b[i + 3] = Math.max(b[i + 3], v[i]);
                }
            }
        }
        return b;
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] a) {
        double length = Math.sqrt(dot(a, a));
        return new double[] {a[0] / length, a[1] / length, a[2] / length};
    }
}
