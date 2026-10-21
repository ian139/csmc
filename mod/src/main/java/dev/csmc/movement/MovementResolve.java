package dev.csmc.movement;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import dev.csmc.movement.physics.ConvexBrushWorld;
import dev.csmc.movement.physics.CsTrace;
import dev.csmc.movement.physics.CsMovementParams;
import dev.csmc.movement.physics.CsUnits;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the CS player hull out of the combined collision world (Minecraft collision boxes plus analytic
 * {@code MovementGeometry} solids) where vanilla collision cannot: pushing a hull that starts inside an analytic
 * solid back out, and choosing a commit when vanilla's axis-separated replay of a CS move disagrees with it.
 * Side-agnostic and allocation-light; used by the client simulation.
 */
public final class MovementResolve {
    /**
     * Largest total depenetration, in blocks. Covers the gap between an analytic surface and its inscribed voxel
     * approximation (at most one 1/16 voxel step on ramps, plus rounding) and stays inside the collection margin
     * of the client's collision query; anything deeper is left to vanilla's own push-out.
     */
    public static final double MAX_DEPENETRATION = 0.5;
    /** Clearance added past the separating plane, in blocks (about the {@code DIST_EPSILON} of 0.03125 units). */
    public static final double SEPARATION_MARGIN = 1.0 / 1024.0;
    private static final int MAX_PASSES = 4;
    /** A plane overlaps the box when it reaches more than this far (blocks) into it. */
    private static final double OVERLAP_TOLERANCE = 1.0E-7;

    private MovementResolve() {}

    /**
     * Collision-collection radius in blocks for one Minecraft tick: current speed, plus twice the held max speed
     * (accelerating from rest and sliding), jump impulse and gravity over the tick, plus step and duck shifts (units)
     * and a 0.25-block margin. Bounded because the held speed is limited to {@code WeaponData.MAX_ALLOWED_SPEED}.
     */
    public static double collisionReach(double speed, double maxSpeed, CsMovementParams params, double stepHeight,
            double duckShift) {
        return CsUnits.toBlocks((speed + 2.0 * maxSpeed + params.jumpImpulse() + params.gravity())
                / CsUnits.TICKS_PER_SECOND + stepHeight + duckShift) + 0.25;
    }

    /** True when {@code box} (block coordinates) overlaps a solid of {@code world}. */
    public static boolean overlaps(ConvexBrushWorld world, AABB box, CsTrace trace) {
        double x = CsUnits.toUnits((box.minX + box.maxX) * 0.5);
        double y = CsUnits.toUnits(box.minY);
        double z = CsUnits.toUnits((box.minZ + box.maxZ) * 0.5);
        world.traceHull(x, y, z, x, y, z, CsUnits.toUnits(box.getXsize() * 0.5), CsUnits.toUnits(box.getYsize()),
                trace);
        return trace.startSolid;
    }

    /**
     * Offset (blocks) that moves {@code box} out of the analytic solids {@code brushes[firstAnalytic..]}: each pass
     * finds an analytic solid the box overlaps and moves the box along that solid's face of least penetration
     * (separating-axis push over its planes) plus {@link #SEPARATION_MARGIN}. Returns {@link Vec3#ZERO} when the box
     * is already clear of {@code combined}, and null when it cannot be cleared within {@link #MAX_DEPENETRATION}
     * and {@value #MAX_PASSES} passes, or is stuck in a non-analytic solid (vanilla push-out handles those).
     */
    @Nullable
    public static Vec3 depenetrate(List<ConvexBrush> brushes, int firstAnalytic, ConvexBrushWorld combined,
            AABB box, CsTrace trace) {
        double ox = 0.0;
        double oy = 0.0;
        double oz = 0.0;
        double totalPush = 0.0;
        for (int pass = 0; pass <= MAX_PASSES; pass++) {
            AABB moved = box.move(ox, oy, oz);
            if (!overlaps(combined, moved, trace)) {
                return pass == 0 ? Vec3.ZERO : new Vec3(ox, oy, oz);
            }
            if (pass == MAX_PASSES) return null;
            BrushPlane exit = null;
            double exitDepth = Double.POSITIVE_INFINITY;
            for (int i = firstAnalytic; i < brushes.size(); i++) {
                ConvexBrush brush = brushes.get(i);
                if (!brush.bounds().intersects(moved)) continue;
                BrushPlane least = null;
                double leastDepth = Double.POSITIVE_INFINITY;
                boolean inside = true;
                for (BrushPlane plane : brush.planes()) {
                    double depth = plane.distance() - lowest(plane, moved);
                    if (depth <= OVERLAP_TOLERANCE) {
                        inside = false;
                        break;
                    }
                    if (depth < leastDepth) {
                        leastDepth = depth;
                        least = plane;
                    }
                }
                if (inside && leastDepth < exitDepth) {
                    exitDepth = leastDepth;
                    exit = least;
                }
            }
            if (exit == null) return null;
            double push = exitDepth + SEPARATION_MARGIN;
            totalPush += push;
            if (totalPush > MAX_DEPENETRATION) return null;
            ox += exit.nx() * push;
            oy += exit.ny() * push;
            oz += exit.nz() * push;
        }
        return null;
    }

    /** Smallest {@code n · p} over the corners of {@code box}. */
    private static double lowest(BrushPlane plane, AABB box) {
        double cx = (box.minX + box.maxX) * 0.5;
        double cy = (box.minY + box.maxY) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double reach = Math.abs(plane.nx()) * box.getXsize() * 0.5 + Math.abs(plane.ny()) * box.getYsize() * 0.5
                + Math.abs(plane.nz()) * box.getZsize() * 0.5;
        return plane.nx() * cx + plane.ny() * cy + plane.nz() * cz - reach;
    }

    /**
     * Commit used when vanilla's replay ({@code replay}, from {@code vanillaCollide} of the CS move) is too far from
     * the CS move: {@code replay} itself when its end is clear of {@code combined} and replay-compatible; otherwise
     * the part of {@code replay} the hull can sweep through {@code combined}, if vanilla replays that part within
     * {@code tolerance} blocks; otherwise no move. {@code box} must start clear (see {@link #depenetrate}).
     * The returned normal is the analytic or vanilla surface that stopped the sweep (unit, null when none).
     */
    public static Fallback fallback(ConvexBrushWorld combined, AABB box, Vec3 replay, UnaryOperator<Vec3> vanillaCollide,
            double tolerance, CsTrace trace) {
        if (!overlaps(combined, box.move(replay), trace)
                && vanillaCollide.apply(replay).distanceToSqr(replay) <= tolerance * tolerance) {
            return new Fallback(replay, null);
        }
        double sx = CsUnits.toUnits((box.minX + box.maxX) * 0.5);
        double sy = CsUnits.toUnits(box.minY);
        double sz = CsUnits.toUnits((box.minZ + box.maxZ) * 0.5);
        combined.traceHull(sx, sy, sz, sx + CsUnits.toUnits(replay.x), sy + CsUnits.toUnits(replay.y),
                sz + CsUnits.toUnits(replay.z), CsUnits.toUnits(box.getXsize() * 0.5),
                CsUnits.toUnits(box.getYsize()), trace);
        if (trace.startSolid) return new Fallback(Vec3.ZERO, null);
        Vec3 swept = new Vec3(CsUnits.toBlocks(trace.end.x - sx), CsUnits.toBlocks(trace.end.y - sy),
                CsUnits.toBlocks(trace.end.z - sz));
        Vec3 normal = trace.hit() ? new Vec3(trace.normal.x, trace.normal.y, trace.normal.z) : null;
        Vec3 replayed = vanillaCollide.apply(swept);
        if (replayed.distanceToSqr(swept) > tolerance * tolerance || overlaps(combined, box.move(swept), trace)) {
            return new Fallback(Vec3.ZERO, null);
        }
        return new Fallback(swept, normal);
    }

    /** A committed fallback move (blocks) and the unit normal of the surface that stopped it, if any. */
    public record Fallback(Vec3 move, @Nullable Vec3 normal) {}
}
