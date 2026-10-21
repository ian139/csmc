package dev.csmc.client.mixin;

import dev.csmc.client.equipment.EquipmentClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Simple third-person pose for a held or in-use healthshot/C4 ({@link EquipmentClient#armPose}). */
@Mixin(PlayerRenderer.class)
abstract class EquipmentArmPoseMixin {
    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private static void csmc$equipmentPose(AbstractClientPlayer player, InteractionHand hand,
                                           CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        HumanoidModel.ArmPose pose = EquipmentClient.armPose(player, hand);
        if (pose != null) cir.setReturnValue(pose);
    }
}
