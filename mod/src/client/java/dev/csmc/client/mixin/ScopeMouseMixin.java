package dev.csmc.client.mixin;

import dev.csmc.client.ScopeZoom;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MouseHandler.class)
public abstract class ScopeMouseMixin {
    // Apply after vanilla sensitivity, inversion and cinematic smoothing, without changing options.
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void csmc$scopeMouseDelta(LocalPlayer player, double yaw, double pitch) {
        double ratio = ScopeZoom.zoomRatio();
        player.turn(yaw * ratio, pitch * ratio);
    }
}
