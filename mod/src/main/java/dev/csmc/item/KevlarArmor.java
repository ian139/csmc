package dev.csmc.item;

import dev.csmc.CsmcMod;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

/** Bullet-resistant CS kevlar with iron-equivalent vanilla defense, not a netherite replacement. */
public final class KevlarArmor {
    public static final Holder<ArmorMaterial> MATERIAL = Registry.registerForHolder(
            BuiltInRegistries.ARMOR_MATERIAL, CsmcMod.id("kevlar"),
            new ArmorMaterial(Map.of(ArmorItem.Type.HELMET, 2, ArmorItem.Type.CHESTPLATE, 6),
                    15, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(Items.IRON_INGOT),
                    List.of(new ArmorMaterial.Layer(CsmcMod.id("kevlar"))), 0.0F, 0.1F));
    public static final ArmorItem VEST = Registry.register(BuiltInRegistries.ITEM, CsmcMod.id("kevlar_vest"),
            new ArmorItem(MATERIAL, ArmorItem.Type.CHESTPLATE, new Item.Properties().durability(640)));
    public static final ArmorItem HELMET = Registry.register(BuiltInRegistries.ITEM, CsmcMod.id("kevlar_helmet"),
            new ArmorItem(MATERIAL, ArmorItem.Type.HELMET, new Item.Properties().durability(440)));

    private KevlarArmor() {}

    public static void init() {
        // Loading this class registers its material and items before the registries freeze.
    }
}
