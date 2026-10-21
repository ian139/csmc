package dev.csmc.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.csmc.item.GunItem;
import dev.csmc.item.KnifeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
abstract class GunWalkingBobMixin {
    @Unique private static final float CSMC_GUN_WALKING_BOB_SCALE = 0.08F;
    @Unique private static final float CSMC_GUN_WALKING_BOB_SPEED = 0.5F;

    @Shadow @Final private Minecraft minecraft;

    // Only replace the hand-pass invocation; the world-camera bob and prior hurt pose remain vanilla.
    @WrapWithCondition(method = "renderItemInHand", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private boolean csmc$gunWalkingBob(GameRenderer renderer, PoseStack poses, float partialTick) {
        LocalPlayer heldBy = minecraft.player;
        if (heldBy == null || !(minecraft.getCameraEntity() instanceof Player player)
                || !(heldBy.getMainHandItem().getItem() instanceof GunItem
                || heldBy.getOffhandItem().getItem() instanceof GunItem
                || heldBy.getMainHandItem().getItem() instanceof KnifeItem
                || heldBy.getOffhandItem().getItem() instanceof KnifeItem)) return true;

        // Vanilla shares this pose between hands: a gun or knife attenuates both hands.
        // Grenades, other items and empty hands alone remain unchanged.
        // Match 1.21.1 GameRenderer.bobView with gentler amplitude and slower walking phase.
        float walkDelta = player.walkDist - player.walkDistO;
        float phase = -(player.walkDist + walkDelta * partialTick) * CSMC_GUN_WALKING_BOB_SPEED;
        float amplitude = Mth.lerp(partialTick, player.oBob, player.bob) * CSMC_GUN_WALKING_BOB_SCALE;
        poses.translate(Mth.sin(phase * (float) Math.PI) * amplitude * 0.5F,
                -Math.abs(Mth.cos(phase * (float) Math.PI) * amplitude), 0.0F);
        poses.mulPose(Axis.ZP.rotationDegrees(Mth.sin(phase * (float) Math.PI) * amplitude * 3.0F));
        poses.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.cos(phase * (float) Math.PI - 0.2F) * amplitude) * 5.0F));
        return false;
    }
}
