package dev.csmc.network;

import dev.csmc.CsmcMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Input intent only: no client-supplied ammo, target, damage, timing, or weapon stats. */
public record GunActionPayload(int action) implements CustomPacketPayload {
    public static final int RELEASE = 0;
    public static final int PRESS = 1;
    public static final int RELOAD = 2;
    /** Request a presentation-only inspect; the server confirms or ignores it via {@link GunInspectPayload}. */
    public static final int INSPECT = 3;
    /** Cycle the main-hand weapon's zoom; the server chooses and confirms the level. */
    public static final int ZOOM = 4;
    public static final Type<GunActionPayload> TYPE = new Type<>(CsmcMod.id("gun_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GunActionPayload> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> buf.writeByte(packet.action), buf -> new GunActionPayload(buf.readUnsignedByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
