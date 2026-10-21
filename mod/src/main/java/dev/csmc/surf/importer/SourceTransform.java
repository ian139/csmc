package dev.csmc.surf.importer;

import dev.csmc.movement.geometry.BrushPlane;
import net.minecraft.world.phys.Vec3;

/**
 * Source map space to Minecraft block space. Source is z-up with x east and y north (Hammer's top view); Minecraft is
 * y-up with x east and z south. The axis map {@code (x, y, z) -> (x, z, -y)} is a proper rotation (determinant +1), so
 * outward plane normals stay outward. Lengths are divided by {@link #UNITS_PER_BLOCK}; Source point {@code (0,0,0)}
 * lands on the corner of block {@code origin}.
 *
 * <p>Approximation: 40 Source units per block is the run-wide scale (CS player hull 32 units wide, 72 tall vs
 * Minecraft 0.6 x 1.8 blocks; see docs/dev/surf-import.md). It is not a Valve-defined ratio.
 */
public record SourceTransform(int originX, int originY, int originZ) {
    public static final double UNITS_PER_BLOCK = 40.0;

    /** Source point to Minecraft block coordinates. */
    public Vec3 point(double x, double y, double z) {
        return new Vec3(originX + x / UNITS_PER_BLOCK, originY + z / UNITS_PER_BLOCK, originZ - y / UNITS_PER_BLOCK);
    }

    public Vec3 point(VmfMap.Point p) {
        return point(p.x(), p.y(), p.z());
    }

    /** Direction (unit-free) from Source to Minecraft axes. */
    public static Vec3 direction(double x, double y, double z) {
        return new Vec3(x, z, -y);
    }

    /**
     * The plane through three Source points with vbsp's outward normal {@code (p0-p1) x (p2-p1)}
     * ({@code CMapFile::PlaneFromPoints}, utils/vbsp/map.cpp, Source SDK 2013), in Minecraft block space.
     * Returns null for degenerate (collinear or repeated) points.
     */
    public BrushPlane plane(VmfMap.Point p0, VmfMap.Point p1, VmfMap.Point p2) {
        double t1x = p0.x() - p1.x(), t1y = p0.y() - p1.y(), t1z = p0.z() - p1.z();
        double t2x = p2.x() - p1.x(), t2y = p2.y() - p1.y(), t2z = p2.z() - p1.z();
        double nx = t1y * t2z - t1z * t2y;
        double ny = t1z * t2x - t1x * t2z;
        double nz = t1x * t2y - t1y * t2x;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double scale = Math.max(Math.sqrt(t1x * t1x + t1y * t1y + t1z * t1z), 1e-9)
                * Math.max(Math.sqrt(t2x * t2x + t2y * t2y + t2z * t2z), 1e-9);
        if (!(length > 1e-9 * scale) || !Double.isFinite(length)) return null;
        Vec3 normal = direction(nx / length, ny / length, nz / length);
        normal = snapUnit(normal);
        Vec3 onPlane = point(p0);
        return BrushPlane.of(normal.x, normal.y, normal.z, normal.dot(onPlane));
    }

    /**
     * Removes float noise from axis-aligned normals (|component| within 1e-12 of 0 or 1), the same intent as vbsp's
     * {@code SnapPlane}, so an axis face stays exactly axial.
     */
    static Vec3 snapUnit(Vec3 n) {
        double x = Math.abs(n.x) < 1e-12 ? 0 : n.x;
        double y = Math.abs(n.y) < 1e-12 ? 0 : n.y;
        double z = Math.abs(n.z) < 1e-12 ? 0 : n.z;
        double length = Math.sqrt(x * x + y * y + z * z);
        return new Vec3(x / length, y / length, z / length);
    }

    /**
     * Source yaw (degrees, 0 = +x east, counter-clockwise seen from above) to Minecraft yaw (0 = +z south, 90 = -x
     * west). Derived from the axis map: Minecraft yaw = -90 - Source yaw, wrapped to [-180, 180).
     */
    public static float yaw(double sourceYaw) {
        double yaw = -90.0 - sourceYaw;
        yaw = ((yaw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return (float) yaw;
    }

    /** Source and Minecraft pitch both grow when looking down; wrapped to [-180, 180) then clamped to [-90, 90]. */
    public static float pitch(double sourcePitch) {
        double pitch = ((sourcePitch + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return (float) Math.max(-90.0, Math.min(90.0, pitch));
    }
}
