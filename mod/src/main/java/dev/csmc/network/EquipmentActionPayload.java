package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Start/renew or cancel an equipment action. Clients never supply timing or a planting position. */
public record EquipmentActionPayload(String action, int entityId, int sequence) implements CustomPacketPayload {
    public static final Type<EquipmentActionPayload> TYPE = new Type<>(CsmcMod.id("equipment_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EquipmentActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.action, 16);
                buf.writeVarInt(packet.entityId);
                buf.writeVarInt(packet.sequence);
            }, buf -> new EquipmentActionPayload(buf.readUtf(16), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
