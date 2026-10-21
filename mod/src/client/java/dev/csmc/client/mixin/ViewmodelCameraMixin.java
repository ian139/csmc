package dev.csmc.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.client.render.CachedGunRendering;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the first-person camera-space pose (view bob and hand sway applied, before vanilla's per-hand equip, swing
 * and arm placement), so CS2 gun and knife clips can place the weapon and arms in the dedicated viewmodel pass.
 */
@Mixin(ItemInHandRenderer.class)
abstract class ViewmodelCameraMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void csmc$beginViewmodel(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
                                     float swingProgress, ItemStack stack, float equipProgress, PoseStack poses,
                                     MultiBufferSource buffers, int light, CallbackInfo ci) {
        CachedGunRendering.beginViewmodel(poses.last().pose());
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"))
    private void csmc$endViewmodel(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
                                   float swingProgress, ItemStack stack, float equipProgress, PoseStack poses,
                                   MultiBufferSource buffers, int light, CallbackInfo ci) {
        CachedGunRendering.endViewmodel();
    }
}
