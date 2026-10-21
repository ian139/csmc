package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-chosen view delta; unlike position teleports, recoil must not gate movement on an ACK. */
public record GunRecoilPayload(float pitch, float yaw) implements CustomPacketPayload {
    public static final Type<GunRecoilPayload> TYPE = new Type<>(CsmcMod.id("gun_recoil"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GunRecoilPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeFloat(packet.pitch);
                buf.writeFloat(packet.yaw);
            }, buf -> new GunRecoilPayload(buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
