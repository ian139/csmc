package dev.csmc.item;

import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.WeaponSkinItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;

public final class GunItem extends Item implements SkinnableWeapon {
    private final ResourceLocation weaponId;

    public GunItem(ResourceLocation weaponId) {
        super(new Item.Properties().stacksTo(1));
        this.weaponId = weaponId;
    }

    @Override
    public ResourceLocation weaponId() {
        return weaponId;
    }

    /** Logical server only: absence means new, while an explicit zero remains an empty magazine. */
    public void initializeMagazine(ItemStack stack) {
        if (stack.has(CsmcMod.MAGAZINE)) return;
        WeaponData data = WeaponRegistry.get(weaponId);
        if (data != null) stack.set(CsmcMod.MAGAZINE, data.magazineSize());
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!level.isClientSide) initializeMagazine(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.csmc.magazine", stack.getOrDefault(CsmcMod.MAGAZINE, 0))
                .withStyle(ChatFormatting.GRAY));
        SkinData skin = stack.get(SkinGameplay.SKIN);
        if (skin != null) WeaponSkinItem.appendSkinLines(skin, null, lines);
        lines.add(Component.translatable("tooltip.csmc.controls").withStyle(ChatFormatting.DARK_GRAY));
    }
}
