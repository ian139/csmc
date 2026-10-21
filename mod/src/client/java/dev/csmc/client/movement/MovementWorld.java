package dev.csmc.client.movement;

import dev.csmc.movement.geometry.ConvexBrush;
import dev.csmc.movement.geometry.MovementGeometry;
import dev.csmc.movement.physics.ConvexBrushWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Collision world of one client-side CS movement query: Minecraft's own collision boxes for the entity (block
 * shapes as {@code Level.getBlockCollisions} reports them for this entity, so analytic blocks contribute nothing,
 * collidable entities such as boats and shulkers, and the world border once the entity is near it) as axis-aligned
 * {@link ConvexBrush#box} solids, plus every {@link MovementGeometry} solid, traced by {@link ConvexBrushWorld}.
 * Reuses its list and world between ticks.
 */
final class MovementWorld {
    private final List<ConvexBrush> brushes = new ArrayList<>();
    private ConvexBrushWorld world;
    private int vanillaSolids;
    private int analyticSolids;

    /** Collects the solids that may intersect {@code region}; {@code vanilla=false} keeps only movement geometry. */
    ConvexBrushWorld collect(Level level, Entity entity, AABB region, boolean vanilla) {
        brushes.clear();
        if (vanilla) {
            for (VoxelShape shape : level.getBlockCollisions(entity, region)) addShape(shape);
            for (VoxelShape shape : level.getEntityCollisions(entity, region)) addShape(shape);
            addBorder(level.getWorldBorder(), entity, region);
        }
        vanillaSolids = brushes.size();
        MovementGeometry.collectAll(level, region, brushes::add);
        analyticSolids = brushes.size() - vanillaSolids;
        if (world == null) {
            world = new ConvexBrushWorld(brushes);
        } else {
            world.reset(brushes);
        }
        return world;
    }

    /** Movement-geometry solids found by the last {@link #collect}. */
    int analyticSolids() {
        return analyticSolids;
    }

    /** Solids of the last {@link #collect}: Minecraft boxes first, then {@link #analyticSolids} movement solids. */
    List<ConvexBrush> brushes() {
        return brushes;
    }

    /** Index of the first movement-geometry solid in {@link #brushes}. */
    int vanillaSolids() {
        return vanillaSolids;
    }

    private void addShape(VoxelShape shape) {
        for (AABB box : shape.toAabbs()) brushes.add(ConvexBrush.box(box));
    }

    /**
     * Vanilla collides an entity with the border only while it is inside and close to it
     * ({@code Entity.collideBoundingBox}); the outside is represented by slabs covering the part of {@code region}
     * beyond each side.
     */
    private void addBorder(WorldBorder border, Entity entity, AABB region) {
        if (!border.isInsideCloseToBorder(entity, region)) return;
        double minX = border.getMinX();
        double maxX = border.getMaxX();
        double minZ = border.getMinZ();
        double maxZ = border.getMaxZ();
        if (region.minX < minX) {
            brushes.add(ConvexBrush.box(new AABB(region.minX, region.minY, region.minZ, minX, region.maxY, region.maxZ)));
        }
        if (region.maxX > maxX) {
            brushes.add(ConvexBrush.box(new AABB(maxX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ)));
        }
        if (region.minZ < minZ) {
            brushes.add(ConvexBrush.box(new AABB(region.minX, region.minY, region.minZ, region.maxX, region.maxY, minZ)));
        }
        if (region.maxZ > maxZ) {
            brushes.add(ConvexBrush.box(new AABB(region.minX, region.minY, maxZ, region.maxX, region.maxY, region.maxZ)));
        }
    }
}
