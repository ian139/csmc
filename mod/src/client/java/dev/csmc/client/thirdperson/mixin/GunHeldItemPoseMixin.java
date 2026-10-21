package dev.csmc.client.thirdperson.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.client.thirdperson.blend.HeldItemPoseCorrection;
import dev.csmc.item.GunItem;
import dev.csmc.item.KnifeItem;
import dev.csmc.thirdperson.guns.GunPoseMath;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Corrects the hanging-arm item basis for posed main-hand weapons; keeps each family's authored tip angle. */
@Mixin(ItemInHandLayer.class)
abstract class GunHeldItemPoseMixin {
    /** Render-thread scratch; {@link PoseStack#mulPose} folds it into the matrices immediately and never retains it. */
    @Unique
    private static final Quaternionf CSMC$CORRECTION = new Quaternionf();

    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void csmc$alignRaisedWeapon(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                   HumanoidArm arm, PoseStack poses, MultiBufferSource buffers, int light,
                                   CallbackInfo ci) {
        if (!(entity instanceof AbstractClientPlayer player)
                || !(stack.getItem() instanceof GunItem || stack.getItem() instanceof KnifeItem)
                || arm != entity.getMainArm() || stack != entity.getMainHandItem() || context.firstPerson()) return;
        float correction = GunPoseMath.heldItemCorrection(HeldItemPoseCorrection.mainArmX(player),
                HeldItemPoseCorrection.headPitch(player), HeldItemPoseCorrection.weight(player));
        if (correction != 0) poses.mulPose(CSMC$CORRECTION.rotationX(correction));
    }
}
