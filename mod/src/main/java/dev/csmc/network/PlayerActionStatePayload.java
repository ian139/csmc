package dev.csmc.network;

import dev.csmc.CsmcMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to tracking clients: third-person-visible action state of player {@code entityId}. {@code SCOPE}: value =
 * scope level (0 unscoped). {@code GRENADE_PRIME}: value = 0 strong (attack), 1 underhand/lob (use), 2 both.
 * {@code GRENADE_THROW}: value = same strength code. {@code GRENADE_CANCEL}: value ignored. Unknown kinds are ignored
 * by clients. Sent to players tracking the actor, not to the actor (its own first-person path has local state).
 */
public record PlayerActionStatePayload(int entityId, int kind, int value) implements CustomPacketPayload {
    public static final int SCOPE = 0;
    public static final int GRENADE_PRIME = 1;
    public static final int GRENADE_THROW = 2;
    public static final int GRENADE_CANCEL = 3;

    public static final Type<PlayerActionStatePayload> TYPE = new Type<>(CsmcMod.id("player_action_state"));
    public static final StreamCodec<ByteBuf, PlayerActionStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlayerActionStatePayload::entityId,
            ByteBufCodecs.VAR_INT, PlayerActionStatePayload::kind,
            ByteBufCodecs.VAR_INT, PlayerActionStatePayload::value,
            PlayerActionStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
