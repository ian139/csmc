package dev.csmc.client.grenade.mixin;

import dev.csmc.client.grenade.GrenadeThrowClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A main-hand grenade turns attack/use into prime-and-release: no punching, mining or vanilla item use. */
@Mixin(Minecraft.class)
public abstract class GrenadeInputMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void csmc$grenadeNoAttack(CallbackInfoReturnable<Boolean> callback) {
        if (GrenadeThrowClient.ownsInput((Minecraft) (Object) this)) callback.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void csmc$grenadeNoMining(boolean leftClick, CallbackInfo callback) {
        Minecraft client = (Minecraft) (Object) this;
        if (GrenadeThrowClient.ownsInput(client)) {
            if (client.gameMode != null) client.gameMode.stopDestroyBlock();
            callback.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void csmc$grenadeNoUse(CallbackInfo callback) {
        if (GrenadeThrowClient.ownsInput((Minecraft) (Object) this)) callback.cancel();
    }
}
