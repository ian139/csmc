package dev.csmc.client.mixin;

import dev.csmc.client.gloves.GloveClient;
import dev.csmc.gloves.GloveSlot;
import dev.csmc.viewmodel.gloves.GloveView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creative survival tab: vanilla wraps every inventory-menu slot and would put the glove slot (menu index 46) on the
 * hotbar row; it goes left of the offhand slot instead, with a drawn frame and the empty-slot tooltip.
 */
@Mixin(CreativeModeInventoryScreen.class)
abstract class CreativeInventoryGloveMixin
        extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {
    private static final String WRAPPER = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen$SlotWrapper;"
            + "<init>(Lnet/minecraft/world/inventory/Slot;III)V";

    private CreativeInventoryGloveMixin(CreativeModeInventoryScreen.ItemPickerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Shadow public abstract boolean isInventoryOpen();

    @ModifyArg(method = "selectTab", at = @At(value = "INVOKE", target = WRAPPER), index = 2)
    private int csmc$gloveSlotX(Slot target, int index, int x, int y) {
        return GloveView.creativeX(index, GloveSlot.MENU_INDEX, x);
    }

    @ModifyArg(method = "selectTab", at = @At(value = "INVOKE", target = WRAPPER), index = 3)
    private int csmc$gloveSlotY(Slot target, int index, int x, int y) {
        return GloveView.creativeY(index, GloveSlot.MENU_INDEX, y);
    }

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void csmc$gloveSlotFrame(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (isInventoryOpen()) GloveClient.drawFrame(graphics, leftPos + GloveView.CREATIVE_X, topPos + GloveView.CREATIVE_Y);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void csmc$gloveSlotTooltip(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (isInventoryOpen()) GloveClient.renderEmptyTooltip(graphics, font, hoveredSlot, menu.getCarried(), mouseX, mouseY);
    }
}
