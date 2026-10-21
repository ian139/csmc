package dev.csmc.surf;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import dev.csmc.movement.geometry.MovementGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

/**
 * Emits one world-space {@link ConvexBrush} per surf ramp cell touching the region: the cell's planes translated to
 * world space (exact for canonical planes, so neighbouring cells cut from one world plane share bit-identical
 * coefficients) followed by the six faces of the cell. Bounds are the solid's tight box.
 */
public final class SurfRampGeometryProvider implements MovementGeometry {
    public static final SurfRampGeometryProvider INSTANCE = new SurfRampGeometryProvider();

    private SurfRampGeometryProvider() {}

    @Override
    public void collect(BlockGetter level, AABB region, Consumer<ConvexBrush> sink) {
        int minX = Mth.floor(region.minX), maxX = Mth.floor(region.maxX);
        int minY = Math.max(Mth.floor(region.minY), level.getMinBuildHeight());
        int maxY = Math.min(Mth.floor(region.maxY), level.getMaxBuildHeight() - 1);
        int minZ = Mth.floor(region.minZ), maxZ = Mth.floor(region.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    RampGeometry geometry = SurfRamps.geometry(level, pos);
                    if (geometry != null && !geometry.isEmpty()) sink.accept(brush(geometry, pos.immutable()));
                }
            }
        }
    }

    /** World-space brush of {@code geometry} placed at {@code pos}. */
    public static ConvexBrush brush(RampGeometry geometry, BlockPos pos) {
        List<BrushPlane> planes = new ArrayList<>(geometry.planes().size() + 6);
        for (BrushPlane local : geometry.planes()) planes.add(SurfRamps.toWorld(local, pos));
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        planes.add(new BrushPlane(1, 0, 0, x + 1));
        planes.add(new BrushPlane(-1, 0, 0, -x + 0.0));
        planes.add(new BrushPlane(0, 1, 0, y + 1));
        planes.add(new BrushPlane(0, -1, 0, -y + 0.0));
        planes.add(new BrushPlane(0, 0, 1, z + 1));
        planes.add(new BrushPlane(0, 0, -1, -z + 0.0));
        return new ConvexBrush(planes, geometry.bounds().move(pos));
    }
}
