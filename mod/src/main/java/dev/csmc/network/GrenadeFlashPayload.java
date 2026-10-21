package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-computed flashbang whiteout for one player: peak {@code opacity} in [0, 1], held for
 * {@code holdTicks}, then faded out over {@code fadeTicks}. Severity (distance, line of sight, facing) is
 * decided on the server; the client only renders it.
 */
public record GrenadeFlashPayload(float opacity, int holdTicks, int fadeTicks) implements CustomPacketPayload {
    public static final Type<GrenadeFlashPayload> TYPE = new Type<>(CsmcMod.id("grenade_flash"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GrenadeFlashPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeFloat(packet.opacity);
                buf.writeVarInt(packet.holdTicks);
                buf.writeVarInt(packet.fadeTicks);
            }, buf -> new GrenadeFlashPayload(buf.readFloat(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
