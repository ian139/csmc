package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-confirmed positional grenade sound at the grenade/effect position. {@code grenade} is the canonical
 * grenade id ({@code hegrenade}, ..., {@code decoy}); {@code event} is one of the {@code EVENT_*} names, which
 * match the cache sound manifest action names. Sent to every player near the position (not only players
 * tracking the thrower). Decoy reports are {@link #EVENT_DECOY}, never a firearm fire event.
 */
public record GrenadeSoundPayload(String grenade, String event, double x, double y, double z, float volume, float pitch)
        implements CustomPacketPayload {
    public static final String EVENT_THROW = "throw";
    public static final String EVENT_BOUNCE = "bounce";
    public static final String EVENT_DETONATE = "detonate";
    public static final String EVENT_LOOP = "loop";
    public static final String EVENT_EXTINGUISH = "extinguish";
    public static final String EVENT_DECOY = "decoy";

    public static final Type<GrenadeSoundPayload> TYPE = new Type<>(CsmcMod.id("grenade_sound"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GrenadeSoundPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeUtf(packet.grenade, 64);
                buf.writeUtf(packet.event, 32);
                buf.writeDouble(packet.x);
                buf.writeDouble(packet.y);
                buf.writeDouble(packet.z);
                buf.writeFloat(packet.volume);
                buf.writeFloat(packet.pitch);
            }, buf -> new GrenadeSoundPayload(buf.readUtf(64), buf.readUtf(32), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
