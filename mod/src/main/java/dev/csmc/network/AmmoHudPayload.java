package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Owner-only authoritative ammo state for the main-hand gun in {@code slot}: rounds in the magazine, magazine
 * capacity, and the total matching ammo in the inventory ({@code reserve}). {@code notice} is a one-shot event
 * ({@link #NOTICE_NONE}, {@link #NOTICE_NO_AMMO}, {@link #NOTICE_RELOAD_CANCELLED}). Sent only on change; a
 * {@code magazine} of -1 clears the readout.
 */
public record AmmoHudPayload(int slot, ResourceLocation weaponId, int magazine, int capacity, int reserve,
                             boolean reloading, int notice) implements CustomPacketPayload {
    public static final int NOTICE_NONE = 0;
    public static final int NOTICE_NO_AMMO = 1;
    public static final int NOTICE_RELOAD_CANCELLED = 2;
    public static final Type<AmmoHudPayload> TYPE = new Type<>(CsmcMod.id("ammo_hud"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AmmoHudPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeVarInt(packet.slot);
                buf.writeResourceLocation(packet.weaponId);
                buf.writeVarInt(packet.magazine + 1);
                buf.writeVarInt(packet.capacity);
                buf.writeVarInt(packet.reserve);
                buf.writeBoolean(packet.reloading);
                buf.writeVarInt(packet.notice);
            }, buf -> new AmmoHudPayload(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt() - 1,
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt()));

    public static AmmoHudPayload cleared() {
        return new AmmoHudPayload(-1, CsmcMod.id("none"), -1, 0, 0, false, NOTICE_NONE);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
