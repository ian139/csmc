package dev.csmc.client.mixin;

import dev.csmc.client.gloves.GloveClient;
import dev.csmc.gloves.GloveSlot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Survival inventory: the glove slot's frame (the background texture has none there) and its empty-slot tooltip. */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenGloveMixin extends AbstractContainerScreen<InventoryMenu> {
    private InventoryScreenGloveMixin(InventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void csmc$gloveSlotFrame(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        GloveClient.drawFrame(graphics, leftPos + GloveSlot.SLOT_X, topPos + GloveSlot.SLOT_Y);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void csmc$gloveSlotTooltip(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GloveClient.renderEmptyTooltip(graphics, font, hoveredSlot, menu.getCarried(), mouseX, mouseY);
    }
}
