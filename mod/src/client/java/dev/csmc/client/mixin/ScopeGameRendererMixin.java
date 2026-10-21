package dev.csmc.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.client.ScopeZoom;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class ScopeGameRendererMixin {
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private double csmc$scopeFov(double normalFov, Camera camera, float partialTick, boolean worldProjection) {
        return worldProjection ? ScopeZoom.fieldOfView(normalFov) : normalFov;
    }

    // Skip both hands/weapons, not GameRenderer's underwater/fire/suffocation screen effects.
    @WrapWithCondition(method = "renderItemInHand", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private boolean csmc$hideScopedHands(ItemInHandRenderer renderer, float partialTick, PoseStack pose,
                                         MultiBufferSource.BufferSource buffers, LocalPlayer player, int light) {
        return !ScopeZoom.isScoped();
    }
}
