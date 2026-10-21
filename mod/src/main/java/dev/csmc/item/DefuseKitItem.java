package dev.csmc.item;

import net.minecraft.world.item.Item;

/** Reusable passive equipment; carrying it anywhere in the inventory halves defuse time. */
public final class DefuseKitItem extends Item {
    public DefuseKitItem() { super(new Item.Properties().stacksTo(1)); }
}
