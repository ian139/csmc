package dev.csmc.client.mixin;

import dev.csmc.client.ScopeZoom;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ScopeMinecraftMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void csmc$blockScopedUse(CallbackInfo callback) {
        if (ScopeZoom.blocksUse((Minecraft) (Object) this)) callback.cancel();
    }

    @Inject(method = "handleKeybinds", at = @At("TAIL"))
    private void csmc$scopePresses(CallbackInfo callback) {
        ScopeZoom.handleUseInput((Minecraft) (Object) this);
    }

    @Inject(method = "updateLevelInEngines", at = @At("HEAD"))
    private void csmc$clearScopeOnWorldChange(CallbackInfo callback) {
        ScopeZoom.clear();
    }
}
