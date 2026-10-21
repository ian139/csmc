package dev.csmc.client.mixin;

import dev.csmc.client.KnifeClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A main-hand knife turns attack/use into slash/stab: no punching, mining or vanilla item use. */
@Mixin(Minecraft.class)
abstract class KnifeInputMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void csmc$knifeNoAttack(CallbackInfoReturnable<Boolean> callback) {
        if (KnifeClient.ownsInput((Minecraft) (Object) this)) callback.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void csmc$knifeNoMining(boolean leftClick, CallbackInfo callback) {
        Minecraft client = (Minecraft) (Object) this;
        if (KnifeClient.ownsInput(client)) {
            if (client.gameMode != null) client.gameMode.stopDestroyBlock();
            callback.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void csmc$knifeNoUse(CallbackInfo callback) {
        if (KnifeClient.ownsInput((Minecraft) (Object) this)) callback.cancel();
    }
}
