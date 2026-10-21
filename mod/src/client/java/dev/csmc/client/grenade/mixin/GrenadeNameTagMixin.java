package dev.csmc.client.grenade.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.client.grenade.SmokeView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla name tags draw a see-through layer that would reveal players behind smoke; skip the tag when the camera's
 * line of sight to it crosses dense smoke. Wrapping the call site covers every renderer override (players included).
 */
@Mixin(EntityRenderer.class)
abstract class GrenadeNameTagMixin {
    @WrapWithCondition(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/entity/EntityRenderer;renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V"))
    private boolean csmc$hideNameTagInSmoke(EntityRenderer<?> renderer, Entity entity, Component name, PoseStack poses,
                                            MultiBufferSource buffers, int light, float partialTick) {
        return !SmokeView.hidesNameTag(entity, partialTick);
    }
}
