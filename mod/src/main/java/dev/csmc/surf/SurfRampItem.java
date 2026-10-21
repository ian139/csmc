package dev.csmc.surf;

import dev.csmc.movement.geometry.AnalyticCollision;
import dev.csmc.movement.geometry.BrushPlane;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Surf ramp block item. Before vanilla placement it picks the cell's planes:
 * <ol>
 *   <li>continue an adjacent ramp: the clicked neighbour first, then the other neighbours in
 *   {@link Direction} order; the first whose world planes cut the new cell (non-empty, not the full cell) wins;</li>
 *   <li>otherwise the default slope: rise:run 2:1 rising along the player's horizontal facing from the cell's bottom
 *   edge nearest the player ({@link SurfRamps#defaultGeometry}).</li>
 * </ol>
 * Vanilla's obstruction check sees the empty pre-placement cell, so placement additionally fails when the selected
 * geometry's vanilla (inscribed) collision would overlap an entity that does not use analytic collision; the same
 * geometry is then installed on success.
 */
public final class SurfRampItem extends BlockItem {
    /** Default rise over run for hand placement (63.43°, surfable: steeper than Source's 0.7 ground normal limit). */
    public static final double DEFAULT_RISE_OVER_RUN = 2.0;

    public SurfRampItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (!context.canPlace()) return super.place(context);
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction clicked = context.replacingClickedOnBlock() ? null : context.getClickedFace().getOpposite();
        RampGeometry geometry = SurfRamps.validate(planesFor(level, pos, clicked, context.getHorizontalDirection()));
        if (geometry == null || !isUnobstructed(level, pos, geometry)) return InteractionResult.FAIL;
        InteractionResult result = super.place(context);
        if (result.consumesAction() && level instanceof ServerLevel server && server.getBlockState(pos)
                .is(Surf.SURF_RAMP)) {
            SurfRamps.place(server, pos, geometry.planes());
        }
        return result;
    }

    /**
     * Whether {@code geometry}'s vanilla collision at {@code pos} (the inscribed staircase) is free of building-blocking
     * entities; entities using analytic collision ({@link AnalyticCollision#appliesTo}) omit these boxes in movement
     * queries and never obstruct placement. Mirrors {@code EntityGetter.isUnobstructed}.
     */
    public static boolean isUnobstructed(Level level, BlockPos pos, RampGeometry geometry) {
        VoxelShape shape = geometry.inscribedCollision().move(pos.getX(), pos.getY(), pos.getZ());
        if (shape.isEmpty()) return true;
        for (Entity entity : level.getEntities((Entity) null, shape.bounds())) {
            if (entity.isRemoved() || !entity.blocksBuilding || AnalyticCollision.appliesTo(entity)) continue;
            if (Shapes.joinIsNotEmpty(shape, Shapes.create(entity.getBoundingBox()), BooleanOp.AND)) return false;
        }
        return true;
    }

    /** Local planes for a hand-placed cell at {@code pos}; see the class comment. */
    public static List<BrushPlane> planesFor(BlockGetter level, BlockPos pos, Direction clickedNeighbour,
            Direction facing) {
        List<Direction> order = new ArrayList<>(List.of(Direction.values()));
        if (clickedNeighbour != null) {
            order.remove(clickedNeighbour);
            order.add(0, clickedNeighbour);
        }
        for (Direction direction : order) {
            List<BrushPlane> world = SurfRamps.worldPlanes(level, pos.relative(direction));
            if (world.isEmpty()) continue;
            List<BrushPlane> local = SurfRamps.toLocal(world, pos);
            RampGeometry geometry = SurfRamps.validate(local);
            if (geometry != null && !geometry.isFullCell()) return geometry.planes();
        }
        return defaultPlanes(facing);
    }

    /** Default slope rising towards {@code facing} from the cell's bottom edge on the opposite side. */
    public static List<BrushPlane> defaultPlanes(Direction facing) {
        int fx = facing.getStepX(), fz = facing.getStepZ();
        double nearEdge = (fx < 0 || fz < 0) ? -1.0 : 0.0;
        return List.of(SurfRamps.slopePlane(fx, fz, DEFAULT_RISE_OVER_RUN, nearEdge, 0.0));
    }
}
