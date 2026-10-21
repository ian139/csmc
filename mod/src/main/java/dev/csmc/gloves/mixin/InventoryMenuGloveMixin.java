package dev.csmc.gloves.mixin;

import dev.csmc.gloves.GloveSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the glove slot ({@link GloveSlot#MENU_INDEX}) to the player inventory menu on both sides, and routes
 * shift-clicks: out of the glove slot into the main inventory and hotbar, and a glove from anywhere in the inventory
 * (main, hotbar, offhand) into the empty glove slot.
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuGloveMixin extends AbstractContainerMenu {
    private static final int INVENTORY_START = 9;
    private static final int OFFHAND = 45;

    protected InventoryMenuGloveMixin(MenuType<?> type, int containerId) {
        super(type, containerId);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void csmc$addGloveSlot(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        Slot slot = addSlot(new GloveSlot.GloveMenuSlot(owner));
        if (slot.index != GloveSlot.MENU_INDEX) {
            throw new IllegalStateException("glove slot landed at menu index " + slot.index);
        }
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void csmc$quickMoveGlove(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (index < INVENTORY_START || index > GloveSlot.MENU_INDEX) return;
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        boolean fromGlove = index == GloveSlot.MENU_INDEX;
        if (!fromGlove && (!GloveSlot.accepts(stack) || slots.get(GloveSlot.MENU_INDEX).hasItem())) return;
        ItemStack original = stack.copy();
        boolean moved = fromGlove
                ? moveItemStackTo(stack, INVENTORY_START, OFFHAND, false)
                : moveItemStackTo(stack, GloveSlot.MENU_INDEX, GloveSlot.MENU_INDEX + 1, false);
        if (!moved) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, original);
        else slot.setChanged();
        slot.onTake(player, stack);
        cir.setReturnValue(original);
    }
}
