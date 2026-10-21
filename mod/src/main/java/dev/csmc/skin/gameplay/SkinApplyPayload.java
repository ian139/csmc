package dev.csmc.skin.gameplay;

import dev.csmc.CsmcMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Skin picker request to skin the sender's held gun; {@code paintKit} 0 removes. The server validates everything. */
public record SkinApplyPayload(int paintKit, float wear, int seed) implements CustomPacketPayload {
    public static final Type<SkinApplyPayload> TYPE = new Type<>(CsmcMod.id("skin_apply"));
    public static final StreamCodec<ByteBuf, SkinApplyPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SkinApplyPayload::paintKit,
            ByteBufCodecs.FLOAT, SkinApplyPayload::wear,
            ByteBufCodecs.VAR_INT, SkinApplyPayload::seed, SkinApplyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
