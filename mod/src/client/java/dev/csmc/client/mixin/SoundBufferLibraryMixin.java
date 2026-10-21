package dev.csmc.client.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.csmc.client.sound.CachedSoundFiles;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Serves {@code csmc_cache:} and {@code csmc_cache_mono:} buffers from the local export cache through vanilla's own buffer cache. */
@Mixin(SoundBufferLibrary.class)
abstract class SoundBufferLibraryMixin {
    @Shadow @Final private Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache;

    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void csmc$cachedGunSound(ResourceLocation path, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> result) {
        if (!CachedSoundFiles.cached(path.getNamespace())) return;
        result.setReturnValue(cache.computeIfAbsent(path,
                key -> CompletableFuture.supplyAsync(() -> CachedSoundFiles.decode(key), Util.nonCriticalIoPool())));
    }
}
