package dev.csmc.network;

import dev.csmc.CsmcMod;
import dev.csmc.grenade.GrenadeThrow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client released a primed grenade. Input intent only: which buttons were held ({@code strength}; null for an
 * unknown ordinal, which the server rejects) and a per-connection increasing {@code sequence}. Position, aim and
 * velocity are the server's own.
 */
public record GrenadeThrowPayload(GrenadeThrow.Strength strength, int sequence) implements CustomPacketPayload {
    public static final Type<GrenadeThrowPayload> TYPE = new Type<>(CsmcMod.id("grenade_throw"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GrenadeThrowPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeByte(packet.strength.ordinal());
                buf.writeVarInt(packet.sequence);
            }, buf -> {
                int ordinal = buf.readUnsignedByte();
                GrenadeThrow.Strength[] values = GrenadeThrow.Strength.values();
                return new GrenadeThrowPayload(ordinal < values.length ? values[ordinal] : null, buf.readVarInt());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
