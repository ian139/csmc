package dev.csmc.network;

import dev.csmc.CsmcMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the sender's held grenade was primed ({@code true}) or the prime was cancelled without a throw
 * ({@code false}). Visual intent only; the server validates the holder, rate limits primes and relays accepted
 * transitions to trackers as {@link PlayerActionStatePayload}. Throws travel as {@link GrenadeThrowPayload}.
 */
public record GrenadePrimePayload(boolean primed) implements CustomPacketPayload {
    public static final Type<GrenadePrimePayload> TYPE = new Type<>(CsmcMod.id("grenade_prime"));
    public static final StreamCodec<ByteBuf, GrenadePrimePayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(GrenadePrimePayload::new, GrenadePrimePayload::primed);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
