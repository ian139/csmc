package dev.csmc.movement.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.csmc.movement.geometry.AnalyticBlock;
import dev.csmc.movement.geometry.AnalyticCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Suppress analytic blocks only in the movement iterator (move replay, noCollision, support and pose queries),
 * on both sides. ClipContext and direct shape queries, including explosion exposure and bullets, never use this
 * iterator and must retain solid cover even when their context contains an analytic player.
 */
@Mixin(BlockCollisions.class)
abstract class BlockCollisionsMixin {
    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape csmc$movementShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
            Operation<VoxelShape> original) {
        if (state.getBlock() instanceof AnalyticBlock && context instanceof EntityCollisionContext entityContext
                && AnalyticCollision.appliesTo(entityContext.getEntity())) return Shapes.empty();
        return original.call(state, level, pos, context);
    }
}
