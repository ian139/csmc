package dev.csmc.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.client.render.CachedGunRendering;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carries the entity owner through vanilla's item renderer, which otherwise supplies only the stack. */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void csmc$beginHeldItem(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                  HumanoidArm arm, PoseStack poses, MultiBufferSource buffers, int light,
                                  CallbackInfo ci) {
        CachedGunRendering.renderingEntity(stack == entity.getMainHandItem() ? entity.getId() : -1);
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"))
    private void csmc$endHeldItem(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                HumanoidArm arm, PoseStack poses, MultiBufferSource buffers, int light,
                                CallbackInfo ci) {
        CachedGunRendering.renderingEntity(-1);
    }
}
