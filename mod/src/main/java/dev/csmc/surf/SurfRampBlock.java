package dev.csmc.surf;

import com.mojang.serialization.MapCodec;
import dev.csmc.movement.geometry.AnalyticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Surf ramp cell. Its exact solid (cell ∩ planes from the block entity) is contributed to CS movement through
 * {@link SurfRampGeometryProvider}. Direct shape queries always see the inscribed 1/8 staircase; the movement
 * iterator omits it for analytic entities. The block never occludes neighbours, never suffocates and lets light through.
 */
public final class SurfRampBlock extends BaseEntityBlock implements AnalyticBlock {
    public static final MapCodec<SurfRampBlock> CODEC = simpleCodec(SurfRampBlock::new);

    public SurfRampBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SurfRampBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        RampGeometry geometry = SurfRamps.geometry(level, pos);
        return geometry == null ? Shapes.empty() : geometry.outline();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        RampGeometry geometry = SurfRamps.geometry(level, pos);
        return geometry == null ? Shapes.empty() : geometry.inscribedCollision();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
