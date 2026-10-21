package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-confirmed weapon inspect state for the player entity {@code entityId}. {@code active} starts the inspect
 * animation for {@code weaponId} held in hotbar {@code slot}; {@code false} cancels it (fire, reload, swap, death).
 * Presentation only: inspecting never changes gameplay state.
 */
public record GunInspectPayload(int entityId, ResourceLocation weaponId, int slot, boolean active)
        implements CustomPacketPayload {
    public static final Type<GunInspectPayload> TYPE = new Type<>(CsmcMod.id("gun_inspect"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GunInspectPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeVarInt(packet.entityId);
                buf.writeResourceLocation(packet.weaponId);
                buf.writeVarInt(packet.slot);
                buf.writeBoolean(packet.active);
            }, buf -> new GunInspectPayload(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
