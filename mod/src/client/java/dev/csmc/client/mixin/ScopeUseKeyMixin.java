package dev.csmc.client.mixin;

import dev.csmc.client.ScopeZoom;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyMapping.class)
public abstract class ScopeUseKeyMixin {
    @Inject(method = "setDown", at = @At("HEAD"))
    private void csmc$usePressEdge(boolean down, CallbackInfo callback) {
        ScopeZoom.useKeyDown((KeyMapping) (Object) this, down);
    }
}
