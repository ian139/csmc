package dev.csmc.item;

import dev.csmc.skin.SkinData;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.WeaponSkinItem;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One CS2 knife type. Melee is server-owned ({@code dev.csmc.melee.MeleeSystem}): attack slashes, use stabs, both
 * driven by {@code KnifeActionPayload}. Vanilla attacks and mining are cancelled while it is held; it never breaks
 * blocks, even in creative.
 */
public final class KnifeItem extends Item implements SkinnableWeapon {
    private final ResourceLocation weaponId;

    public KnifeItem(ResourceLocation weaponId) {
        super(new Item.Properties().stacksTo(1));
        this.weaponId = weaponId;
    }

    @Override
    public ResourceLocation weaponId() {
        return weaponId;
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        SkinData skin = stack.get(SkinGameplay.SKIN);
        if (skin != null) WeaponSkinItem.appendSkinLines(skin, null, lines);
        lines.add(Component.translatable("tooltip.csmc.knife_controls").withStyle(ChatFormatting.DARK_GRAY));
    }
}
