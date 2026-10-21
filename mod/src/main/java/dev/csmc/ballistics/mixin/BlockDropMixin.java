package dev.csmc.ballistics.mixin;

import dev.csmc.ballistics.BlockBreakPolicy;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels block item drops spawned while CS damage destroys a block, including the synchronous support
 * cascade it triggers ({@link BlockBreakPolicy#dropsSuppressed}). Every {@code dropResources}/{@code popResource}
 * path ends in this private method.
 */
@Mixin(Block.class)
abstract class BlockDropMixin {
    @Inject(method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true)
    private static void csmc$suppressCsDamageDrops(
            Level level, Supplier<ItemEntity> itemEntity, ItemStack stack, CallbackInfo ci) {
        if (BlockBreakPolicy.dropsSuppressed()) {
            ci.cancel();
        }
    }
}
