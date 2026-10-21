package dev.csmc.skin.gameplay;

import com.mojang.serialization.Codec;
import dev.csmc.CsmcMod;
import dev.csmc.skin.SkinData;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

/** Persistent attachment components and the common/server gameplay entry point. */
public final class SkinGameplay {
    public static final DataComponentType<SkinData> SKIN = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, CsmcMod.id("skin"),
            DataComponentType.<SkinData>builder().persistent(SkinData.CODEC)
                    .networkSynchronized(SkinData.STREAM_CODEC).build());
    /** Canonical firearm path (for example {@code ak47}), present on attachment items. */
    public static final DataComponentType<String> SKIN_WEAPON = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, CsmcMod.id("skin_weapon"),
            DataComponentType.<String>builder().persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
    public static final WeaponSkinItem WEAPON_SKIN = Registry.register(
            BuiltInRegistries.ITEM, CsmcMod.id("weapon_skin"),
            new WeaponSkinItem(new Item.Properties().stacksTo(1)));
    public static final WeaponCaseItem WEAPON_CASE = Registry.register(
            BuiltInRegistries.ITEM, CsmcMod.id("weapon_case"),
            new WeaponCaseItem(new Item.Properties(), WeaponCaseItem.Pool.ALL));
    /** The Loadout Case: rolls only skins for guns the opener carries. */
    public static final WeaponCaseItem INVENTORY_WEAPON_CASE = Registry.register(
            BuiltInRegistries.ITEM, CsmcMod.id("inventory_weapon_case"),
            new WeaponCaseItem(new Item.Properties(), WeaponCaseItem.Pool.LOADOUT));
    public static final RecipeSerializer<SkinCraftingRecipe> SKIN_CRAFTING = Registry.register(
            BuiltInRegistries.RECIPE_SERIALIZER, CsmcMod.id("skin_crafting"),
            new SimpleCraftingRecipeSerializer<>(SkinCraftingRecipe::new));

    private SkinGameplay() {}

    public static void initialize() {
        SkinCommands.register();
        SkinDevHook.register();
        SkinPicker.register();
        PayloadTypeRegistry.playS2C().register(CaseOpeningPayload.TYPE, CaseOpeningPayload.STREAM_CODEC);
    }

    public static ItemStack skin(String weapon, SkinData data) {
        ItemStack stack = new ItemStack(WEAPON_SKIN);
        stack.set(SKIN, data);
        stack.set(SKIN_WEAPON, weapon);
        return stack;
    }
}
