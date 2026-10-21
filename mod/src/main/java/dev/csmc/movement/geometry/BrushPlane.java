package dev.csmc.movement.geometry;

/**
 * One bounding plane of a convex solid in world block coordinates. A point {@code p} lies on the solid's side when
 * {@code nx*p.x + ny*p.y + nz*p.z <= distance}; the unit normal points out of the solid.
 */
public record BrushPlane(double nx, double ny, double nz, double distance) {
    private static final double UNIT_TOLERANCE = 1.0E-6;

    public BrushPlane {
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!Double.isFinite(length) || Math.abs(length - 1.0) > UNIT_TOLERANCE || !Double.isFinite(distance)) {
            throw new IllegalArgumentException("plane needs a finite unit normal and distance: " + nx + "," + ny + ","
                    + nz + " d=" + distance);
        }
    }

    /** Plane through {@code distance} along the normalised {@code (nx, ny, nz)}; the normal must be non-zero. */
    public static BrushPlane of(double nx, double ny, double nz, double distance) {
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 0.0) || !Double.isFinite(length)) {
            throw new IllegalArgumentException("zero or non-finite plane normal");
        }
        return new BrushPlane(nx / length, ny / length, nz / length, distance / length);
    }

    /** Positive outside the solid, negative inside, zero on the plane. */
    public double signedDistance(double x, double y, double z) {
        return nx * x + ny * y + nz * z - distance;
    }

    /** The same plane moved by {@code (dx, dy, dz)} blocks. */
    public BrushPlane translated(double dx, double dy, double dz) {
        return new BrushPlane(nx, ny, nz, distance + nx * dx + ny * dy + nz * dz);
    }
}
