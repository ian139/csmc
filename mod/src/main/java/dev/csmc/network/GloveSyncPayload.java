package dev.csmc.network;

import dev.csmc.CsmcMod;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.gameplay.SkinGameplay;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The glove worn by player {@code entityId} ({@link ItemStack#EMPTY} for none: default arms gloves), sent to that
 * player and every player tracking them whenever it changes, on join, on respawn and when tracking starts. Only the
 * glove item and its finish ({@link SkinGameplay#SKIN}) travel, so the payload stays a few bytes whatever other
 * components the worn stack carries; the decoded stack has exactly those.
 */
public record GloveSyncPayload(int entityId, ItemStack glove) implements CustomPacketPayload {
    public static final Type<GloveSyncPayload> TYPE = new Type<>(CsmcMod.id("glove_sync"));
    private static final StreamCodec<RegistryFriendlyByteBuf, Holder<Item>> ITEM = ByteBufCodecs.holderRegistry(Registries.ITEM);
    public static final StreamCodec<RegistryFriendlyByteBuf, GloveSyncPayload> STREAM_CODEC =
            StreamCodec.of(GloveSyncPayload::write, GloveSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GloveSyncPayload payload) {
        buf.writeVarInt(payload.entityId);
        ItemStack glove = payload.glove;
        buf.writeBoolean(!glove.isEmpty());
        if (glove.isEmpty()) return;
        ITEM.encode(buf, glove.getItemHolder());
        SkinData skin = glove.get(SkinGameplay.SKIN);
        buf.writeBoolean(skin != null);
        if (skin != null) SkinData.STREAM_CODEC.encode(buf, skin);
    }

    private static GloveSyncPayload read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        if (!buf.readBoolean()) return new GloveSyncPayload(entityId, ItemStack.EMPTY);
        ItemStack glove = new ItemStack(ITEM.decode(buf));
        if (buf.readBoolean()) glove.set(SkinGameplay.SKIN, SkinData.STREAM_CODEC.decode(buf));
        return new GloveSyncPayload(entityId, glove);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
