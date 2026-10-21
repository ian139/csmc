package dev.csmc.item;

import dev.csmc.skin.SkinData;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.WeaponSkinItem;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * One CS2 glove type, worn from the glove slot ({@link dev.csmc.gloves.GloveSlot}) next to the offhand slot. It
 * carries a finish like a knife but has no attack behaviour; the worn glove drives the first-person arms' gloves.
 */
public final class GloveItem extends Item implements SkinnableWeapon {
    private final ResourceLocation weaponId;

    public GloveItem(ResourceLocation weaponId) {
        super(new Item.Properties().stacksTo(1));
        this.weaponId = weaponId;
    }

    @Override
    public ResourceLocation weaponId() {
        return weaponId;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        SkinData skin = stack.get(SkinGameplay.SKIN);
        if (skin != null) WeaponSkinItem.appendSkinLines(skin, null, lines);
    }
}
