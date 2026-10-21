package dev.csmc.client.movement.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.csmc.client.movement.LocalMovement;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CS movement has no auto-jump and no sprint. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Shadow
    private int autoJumpTime;

    @Inject(method = "updateAutoJump", at = @At("HEAD"), cancellable = true)
    private void csmc$noAutoJumpInCsMovement(float dx, float dz, CallbackInfo callback) {
        if (LocalMovement.handles((LocalPlayer) (Object) this)) callback.cancel();
    }

    /**
     * An auto-jump vanilla scheduled before CS movement took over would set {@code input.jumping} in this
     * {@code aiStep}; it is dropped before that happens. Keyboard jump input is read by {@code input.tick} later in
     * the same method and is unaffected.
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void csmc$dropPendingAutoJumpInCsMovement(CallbackInfo callback) {
        if (autoJumpTime > 0 && LocalMovement.handles((LocalPlayer) (Object) this)) autoJumpTime = 0;
    }

    @ModifyReturnValue(method = "canStartSprinting", at = @At("RETURN"))
    private boolean csmc$noSprintInCsMovement(boolean canStart) {
        return canStart && !LocalMovement.handles((LocalPlayer) (Object) this);
    }
}
