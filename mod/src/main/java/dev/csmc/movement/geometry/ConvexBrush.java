package dev.csmc.movement.geometry;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.AABB;

/**
 * Convex solid in world block coordinates: the intersection of its planes' half-spaces. {@code bounds} encloses the
 * solid and is only a broad-phase filter; the planes alone define it. Instances are immutable.
 */
public record ConvexBrush(List<BrushPlane> planes, AABB bounds) {
    public ConvexBrush {
        planes = List.copyOf(planes);
        Objects.requireNonNull(bounds, "bounds");
        if (planes.size() < 4) throw new IllegalArgumentException("a closed convex solid needs at least 4 planes");
    }

    /** The six axis planes of {@code box}. */
    public static ConvexBrush box(AABB box) {
        return new ConvexBrush(List.of(
                new BrushPlane(1, 0, 0, box.maxX), new BrushPlane(-1, 0, 0, -box.minX),
                new BrushPlane(0, 1, 0, box.maxY), new BrushPlane(0, -1, 0, -box.minY),
                new BrushPlane(0, 0, 1, box.maxZ), new BrushPlane(0, 0, -1, -box.minZ)), box);
    }
}
