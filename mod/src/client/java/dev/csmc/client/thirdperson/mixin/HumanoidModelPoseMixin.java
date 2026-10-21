package dev.csmc.client.thirdperson.mixin;

import dev.csmc.client.thirdperson.ThirdPersonPoseApplier;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies CSMC third-person poses after vanilla animation (walk, crouch, jump, swim) and before PlayerModel copies the
 * arms/legs onto its sleeve and pants layers, so outer skin layers follow.
 */
@Mixin(HumanoidModel.class)
abstract class HumanoidModelPoseMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void csmc$thirdPersonPose(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch, CallbackInfo ci) {
        ThirdPersonPoseApplier.apply((HumanoidModel<?>) (Object) this, entity, limbSwing, limbSwingAmount, ageInTicks);
    }
}
