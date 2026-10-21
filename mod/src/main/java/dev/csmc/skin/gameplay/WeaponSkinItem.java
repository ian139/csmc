package dev.csmc.skin.gameplay;

import dev.csmc.CsmcMod;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinNames;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class WeaponSkinItem extends Item {
    public WeaponSkinItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        SkinData data = stack.get(SkinGameplay.SKIN);
        String weapon = stack.get(SkinGameplay.SKIN_WEAPON);
        if (data == null || weapon == null) return super.getName(stack);
        MutableComponent name = Component.translatable("item.csmc.weapon_skin.named", weaponName(weapon), kitName(data));
        SkinCatalog.Kit kit = SkinCatalog.byId(data.paintKit()).orElse(null);
        return kit == null ? name : name.withColor(SkinCatalog.tierColor(WeaponCaseItem.pairTier(kit, weapon)));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        SkinData data = stack.get(SkinGameplay.SKIN);
        if (data == null) return;
        appendSkinLines(data, stack.get(SkinGameplay.SKIN_WEAPON), lines);
    }

    /** Skin kit, optional weapon, wear and seed lines; firearms pass a null weapon since they name themselves. */
    public static void appendSkinLines(SkinData data, String weapon, List<Component> lines) {
        lines.add(Component.translatable("tooltip.csmc.skin.kit", kitName(data)).withStyle(ChatFormatting.GRAY));
        if (weapon != null) {
            lines.add(Component.translatable("tooltip.csmc.skin.weapon", weaponName(weapon)).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("tooltip.csmc.skin.wear", data.wearTier(), Float.toString(data.wear()))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.csmc.skin.seed", data.seed()).withStyle(ChatFormatting.GRAY));
    }

    private static Component kitName(SkinData data) {
        SkinCatalog.Kit kit = SkinCatalog.byId(data.paintKit()).orElse(null);
        return kit == null
                ? Component.translatable("tooltip.csmc.skin.unknown_kit", data.paintKit())
                : Component.literal(SkinNames.displayName(kit));
    }

    private static Component weaponName(String weapon) {
        Item gun = CsmcMod.SKINNABLE.get(weapon);
        return gun == null ? Component.literal(weapon) : gun.getDescription();
    }
}
