package dev.csmc.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.csmc.client.KnifeClient;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Attack/use edges in arrival order, so a knife click that starts and ends between two ticks still swings. Only the
 * static {@code KeyMapping.set(Key, boolean)} that keyboard and mouse events dispatch through counts; state
 * restoration ({@code setAll()} when a screen closes, {@code releaseAll()}) calls {@code setDown} directly and must
 * never fake a press. A press for a mapping that is already down (keyboard repeat, including the first repeat after a
 * screen closes and {@code setAll()} restored the held key) is not a fresh edge; releases always pass through.
 */
@Mixin(KeyMapping.class)
abstract class KnifeKeyEdgeMixin {
    @WrapOperation(method = "set", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;setDown(Z)V"))
    private static void csmc$knifeKeyEdge(KeyMapping mapping, boolean down, Operation<Void> original) {
        if (!down || !mapping.isDown()) KnifeClient.keyEdge(mapping, down);
        original.call(mapping, down);
    }
}
