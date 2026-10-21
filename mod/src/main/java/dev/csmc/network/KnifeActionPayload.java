package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Knife input intent only; the server owns cooldown, trace and damage. */
public record KnifeActionPayload(byte action) implements CustomPacketPayload {
    /** Attack key press: light attack. */
    public static final byte SLASH = 0;
    /** Use key press: heavy attack. */
    public static final byte STAB = 1;
    public static final Type<KnifeActionPayload> TYPE = new Type<>(CsmcMod.id("knife_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KnifeActionPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> buf.writeByte(packet.action), buf -> new KnifeActionPayload(buf.readByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
