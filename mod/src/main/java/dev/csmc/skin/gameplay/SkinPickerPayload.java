package dev.csmc.skin.gameplay;

import dev.csmc.CsmcMod;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: open the skin picker ({@code /csmc skins}, no message) or report an apply outcome
 * ({@code message}, shown in an open picker or else in chat).
 */
public record SkinPickerPayload(Optional<Component> message, boolean success) implements CustomPacketPayload {
    public static final Type<SkinPickerPayload> TYPE = new Type<>(CsmcMod.id("skin_picker"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinPickerPayload> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.TRUSTED_OPTIONAL_STREAM_CODEC, SkinPickerPayload::message,
            ByteBufCodecs.BOOL, SkinPickerPayload::success, SkinPickerPayload::new);
    public static final SkinPickerPayload OPEN = new SkinPickerPayload(Optional.empty(), true);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
