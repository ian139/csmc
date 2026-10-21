package dev.csmc.item;

import dev.csmc.equipment.EquipmentSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** Server-timed injection; the exported inject_healthshot clip is 1.9 seconds. */
public final class HealthshotItem extends Item {
    public static final int USE_TICKS = 38;
    public static final int COOLDOWN_TICKS = 20;

    public HealthshotItem() { super(new Item.Properties().stacksTo(16)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server && !EquipmentSystem.startUse(server, hand)) {
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        if (level.isClientSide()) { player.startUsingItem(hand); }
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) { return 72000; }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (entity instanceof ServerPlayer player) { EquipmentSystem.cancel(player); }
    }
}
