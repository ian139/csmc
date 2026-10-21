package dev.csmc.client.grenade.mixin;

import com.google.common.collect.Multimap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Real channel liveness for grenade fire loops. Vanilla's {@code isActive} stays true after a muted category drops a
 * channel (its delete-time entry is never cleared), so loops check {@code instanceToChannel} and remove their own
 * orphaned bookkeeping. Client thread only, outside {@code SoundEngine.tick}.
 */
@Mixin(SoundEngine.class)
public interface GrenadeSoundEngineAccessor {
    @Accessor("instanceToChannel")
    Map<SoundInstance, ?> csmc$instanceToChannel();

    @Accessor("soundDeleteTime")
    Map<SoundInstance, Integer> csmc$soundDeleteTime();

    @Accessor("instanceBySource")
    Multimap<SoundSource, SoundInstance> csmc$instanceBySource();

    @Accessor("tickingSounds")
    List<TickableSoundInstance> csmc$tickingSounds();
}
