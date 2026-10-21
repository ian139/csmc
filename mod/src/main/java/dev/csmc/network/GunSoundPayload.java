package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-confirmed weapon event. Each client renders it from its own local cache (or a vanilla placeholder) and
 * drives first-person viewmodel playback from it.
 * <ul>
 *   <li>{@code durationTicks}: the server reload length for {@link #RELOAD_START}, else 0.</li>
 *   <li>{@code sequence}: for {@link #FIRE}, the shooter's shot number (increasing per player, from 1); else 0.</li>
 *   <li>{@code magazine}: for {@link #FIRE}, the magazine after the shot; for {@link #RELOAD_START}, the magazine
 *       when the reload began; else -1.</li>
 *   <li>{@code rounds}: for {@link #RELOAD_START}, the rounds the reload will load (shells for a shotgun); else 0.</li>
 * </ul>
 */
public record GunSoundPayload(String weapon, byte event, double x, double y, double z, int shooter, int durationTicks,
                              int sequence, int magazine, int rounds)
        implements CustomPacketPayload {
    public static final byte FIRE = 0;
    public static final byte RELOAD_START = 1;
    public static final byte RELOAD_END = 2;
    public static final byte RELOAD_CANCEL = 3;
    public static final byte DRAW = 4;
    public static final byte ZOOM_IN = 5;
    public static final byte ZOOM_OUT = 6;

    public static final Type<GunSoundPayload> TYPE = new Type<>(CsmcMod.id("gun_sound"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GunSoundPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeUtf(packet.weapon, 64);
                buf.writeByte(packet.event);
                buf.writeDouble(packet.x);
                buf.writeDouble(packet.y);
                buf.writeDouble(packet.z);
                buf.writeVarInt(packet.shooter);
                buf.writeVarInt(packet.durationTicks);
                buf.writeVarInt(packet.sequence);
                buf.writeVarInt(packet.magazine + 1);
                buf.writeVarInt(packet.rounds);
            }, buf -> new GunSoundPayload(buf.readUtf(64), buf.readByte(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1,
                    buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
