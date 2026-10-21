package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-confirmed positional action and normalized progress; id is the canonical equipment item id. */
public record EquipmentEventPayload(String id, String event, int entityId, double x, double y, double z,
        float progress) implements CustomPacketPayload {
    public static final Type<EquipmentEventPayload> TYPE = new Type<>(CsmcMod.id("equipment_event"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EquipmentEventPayload> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.id, 32);
                buf.writeUtf(packet.event, 32);
                buf.writeVarInt(packet.entityId);
                buf.writeDouble(packet.x);
                buf.writeDouble(packet.y);
                buf.writeDouble(packet.z);
                buf.writeFloat(packet.progress);
            }, buf -> new EquipmentEventPayload(buf.readUtf(32), buf.readUtf(32), buf.readVarInt(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
