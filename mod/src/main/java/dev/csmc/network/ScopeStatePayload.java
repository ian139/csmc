package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Owner-only authoritative zoom for the main-hand weapon in {@code slot}. {@code levels} advertises scope
 * capability even at level 0; zero levels clears it. {@code fov} is the target CS horizontal FOV in degrees
 * (90 when unscoped), and {@code timeS} is the transition time into the target level.
 */
public record ScopeStatePayload(int slot, ResourceLocation weaponId, int level, int levels, float fov, float timeS)
        implements CustomPacketPayload {
    public static final Type<ScopeStatePayload> TYPE = new Type<>(CsmcMod.id("scope_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScopeStatePayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeVarInt(packet.slot);
                buf.writeResourceLocation(packet.weaponId);
                buf.writeVarInt(packet.level);
                buf.writeVarInt(packet.levels);
                buf.writeFloat(packet.fov);
                buf.writeFloat(packet.timeS);
            }, buf -> new ScopeStatePayload(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt(),
                    buf.readVarInt(), buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
