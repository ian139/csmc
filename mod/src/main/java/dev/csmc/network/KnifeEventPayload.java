package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-confirmed knife event for the attacker {@code entityId} at {@code (x, y, z)}, sent to the attacker and
 * every player tracking them. Presentation only. Per attack the server sends one swing event ({@code slash},
 * {@code stab}, or {@code backstab} instead of either when the hit is a backstab) at the attacker's eye, then at
 * most one impact event ({@code hit} on an entity, {@code hitwall} on a block) at the impact point.
 */
public record KnifeEventPayload(String knifeId, String event, int entityId, double x, double y, double z)
        implements CustomPacketPayload {
    public static final String DEPLOY = "deploy";
    public static final String SLASH = "slash";
    public static final String STAB = "stab";
    public static final String HIT = "hit";
    public static final String HITWALL = "hitwall";
    /** Stab backstab; a slash backstab is {@link #SLASH_BACKSTAB}. */
    public static final String BACKSTAB = "backstab";
    public static final String SLASH_BACKSTAB = "slash_backstab";
    public static final String INSPECT = "inspect";
    public static final Type<KnifeEventPayload> TYPE = new Type<>(CsmcMod.id("knife_event"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KnifeEventPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeUtf(packet.knifeId, 64);
                buf.writeUtf(packet.event, 16);
                buf.writeVarInt(packet.entityId);
                buf.writeDouble(packet.x);
                buf.writeDouble(packet.y);
                buf.writeDouble(packet.z);
            }, buf -> new KnifeEventPayload(buf.readUtf(64), buf.readUtf(16), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
