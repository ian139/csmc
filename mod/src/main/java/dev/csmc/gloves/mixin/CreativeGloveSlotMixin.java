package dev.csmc.gloves.mixin;

import dev.csmc.gloves.GloveSlot;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creative inventory edits of the glove slot: vanilla only accepts creative slots 1-45, so slot
 * {@link GloveSlot#MENU_INDEX} is handled here with the same creative and feature checks, accepting only an empty
 * stack or exactly one glove.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CreativeGloveSlotMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void csmc$setGloveSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (packet.slotNum() != GloveSlot.MENU_INDEX) return;
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.serverLevel());
        ci.cancel();
        if (!player.gameMode.isCreative()) return;
        ItemStack stack = packet.itemStack();
        if (!stack.isItemEnabled(player.level().enabledFeatures())) return;
        if (!stack.isEmpty() && !(GloveSlot.accepts(stack) && stack.getCount() == 1)) return;
        player.inventoryMenu.getSlot(GloveSlot.MENU_INDEX).setByPlayer(stack);
        player.inventoryMenu.broadcastChanges();
    }
}
