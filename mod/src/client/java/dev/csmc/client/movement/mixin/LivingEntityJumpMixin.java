package dev.csmc.client.movement.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.csmc.client.movement.LocalMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * CS movement jumps inside its own simulation; vanilla's ground jump would add a second impulse. The suppressed
 * jump's {@code noJumpDelay = 10} cooldown write (the second {@code noJumpDelay} store in {@code aiStep}, right
 * after {@code jumpFromGround()}) is suppressed with it, so switching movement off never leaves a cooldown for a
 * jump that did not happen; an existing vanilla cooldown keeps counting down untouched.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityJumpMixin {
    @WrapWithCondition(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;jumpFromGround()V"))
    private boolean csmc$noVanillaJumpInCsMovement(LivingEntity self) {
        return !csmc$csJump(self);
    }

    @WrapWithCondition(method = "aiStep", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/LivingEntity;noJumpDelay:I", opcode = Opcodes.PUTFIELD,
            ordinal = 1))
    private boolean csmc$noVanillaJumpCooldownInCsMovement(LivingEntity self, int delay) {
        return !csmc$csJump(self);
    }

    private static boolean csmc$csJump(LivingEntity self) {
        return self instanceof LocalPlayer player && LocalMovement.handles(player);
    }
}
