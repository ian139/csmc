package dev.csmc.client.grenade.mixin;

import dev.csmc.client.grenade.GrenadeThrowClient;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attack/use edges in arrival order, so a grenade click that starts and ends between two ticks still throws. */
@Mixin(KeyMapping.class)
public abstract class GrenadeKeyEdgeMixin {
    @Inject(method = "setDown", at = @At("HEAD"))
    private void csmc$grenadeKeyEdge(boolean down, CallbackInfo callback) {
        GrenadeThrowClient.keyEdge((KeyMapping) (Object) this, down);
    }
}
