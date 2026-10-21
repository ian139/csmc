package dev.csmc.item;

import dev.csmc.grenade.GrenadeKind;
import dev.csmc.grenade.GrenadeThrow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A throwable CS2 utility grenade. Not a firearm: no magazine, ammunition, recoil or scope. Thrown CS-style by
 * priming with attack/use and releasing (client {@code GrenadeThrowClient}); the release sends only the button
 * strength and the server computes and spawns the only projectile ({@link GrenadeThrow#handle}). Vanilla item
 * use does nothing. Survival consumes exactly one; creative does not. All grenade items share a short cooldown.
 */
public final class GrenadeItem extends Item {
    /** Authored stack size (CS carry limits do not map onto Minecraft inventories). */
    public static final int MAX_STACK = 16;

    private final GrenadeKind kind;

    public GrenadeItem(GrenadeKind kind) {
        super(new Item.Properties().stacksTo(MAX_STACK));
        this.kind = kind;
    }

    public GrenadeKind kind() {
        return kind;
    }

    /** Attack primes the grenade; it never breaks blocks. */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }
}
