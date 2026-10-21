package dev.csmc.skin.gameplay;

import dev.csmc.item.SkinnableWeapon;
import dev.csmc.skin.SkinData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** A gun or knife plus its attachment applies/swaps; a lone skinned gun or knife removes the skin. */
public final class SkinCraftingRecipe extends CustomRecipe {
    public SkinCraftingRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return gunSlot(input) >= 0;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int gunSlot = gunSlot(input);
        if (gunSlot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = input.getItem(gunSlot).copyWithCount(1);
        SkinData applied = null;
        for (int slot = 0; slot < input.size(); slot++) {
            if (slot != gunSlot && !input.getItem(slot).isEmpty()) {
                applied = input.getItem(slot).get(SkinGameplay.SKIN);
                break;
            }
        }
        // The skin and its weapon tag are one pair on the gun (the skin picker writes both).
        if (applied == null) {
            result.remove(SkinGameplay.SKIN);
            result.remove(SkinGameplay.SKIN_WEAPON);
        } else {
            result.set(SkinGameplay.SKIN, applied);
            result.set(SkinGameplay.SKIN_WEAPON, ((SkinnableWeapon) result.getItem()).weaponId().getPath());
        }
        return result;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        int slot = gunSlot(input);
        if (slot >= 0) {
            ItemStack gun = input.getItem(slot);
            SkinData previous = gun.get(SkinGameplay.SKIN);
            if (previous != null) {
                remaining.set(slot, SkinGameplay.skin(((SkinnableWeapon) gun.getItem()).weaponId().getPath(), previous));
            }
        }
        return remaining;
    }

    /** Returns the sole gun slot only for a complete, valid recipe; never consults the local cache. */
    private static int gunSlot(CraftingInput input) {
        int gunSlot = -1;
        int skinSlot = -1;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof SkinnableWeapon) {
                if (gunSlot >= 0) {
                    return -1;
                }
                gunSlot = slot;
            } else if (stack.is(SkinGameplay.WEAPON_SKIN) && stack.has(SkinGameplay.SKIN)) {
                if (skinSlot >= 0) {
                    return -1;
                }
                skinSlot = slot;
            } else {
                return -1;
            }
        }
        if (gunSlot < 0) {
            return -1;
        }
        ItemStack gun = input.getItem(gunSlot);
        if (skinSlot < 0) {
            return gun.has(SkinGameplay.SKIN) ? gunSlot : -1;
        }
        String weapon = ((SkinnableWeapon) gun.getItem()).weaponId().getPath();
        return weapon.equals(input.getItem(skinSlot).get(SkinGameplay.SKIN_WEAPON)) ? gunSlot : -1;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 2 && height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SkinGameplay.SKIN_CRAFTING;
    }
}
