package dev.csmc.client.grenade.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.csmc.client.grenade.SmokeView;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dense grey fog while the camera is inside a smoke cloud; per-frame render state only. */
@Mixin(FogRenderer.class)
abstract class GrenadeFogRendererMixin {
    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void csmc$smokeFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog,
                                      float partialTick, CallbackInfo ci) {
        SmokeView.applyFog(camera);
    }

    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void csmc$smokeColor(Camera camera, float partialTick, ClientLevel level, int renderDistance,
                                        float darkenWorld, CallbackInfo ci) {
        float blend = SmokeView.fogBlend();
        if (blend <= 0) return;
        float grey = SmokeView.fogGrey();
        fogRed = Mth.lerp(blend, fogRed, grey);
        fogGreen = Mth.lerp(blend, fogGreen, grey);
        fogBlue = Mth.lerp(blend, fogBlue, grey * 1.03f);
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }
}
