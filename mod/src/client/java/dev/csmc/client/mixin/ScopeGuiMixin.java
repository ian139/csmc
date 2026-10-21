package dev.csmc.client.mixin;

import dev.csmc.client.ScopeZoom;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class ScopeGuiMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void csmc$scopeReticle(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
        if (ScopeZoom.isScoped()) {
            ScopeZoom.render(graphics);
            callback.cancel();
        }
    }
}
