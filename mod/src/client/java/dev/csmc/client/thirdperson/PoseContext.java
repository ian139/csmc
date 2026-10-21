package dev.csmc.client.thirdperson;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Per-frame inputs for {@link PoseProvider}s. {@code headPitch} is the model's head X rotation in radians (aim).
 * Movement facts come from the rendered entity, so they hold for vanilla and CS-style movement alike (remote players
 * render from server positions): {@code airborne} = not on ground and not a passenger; {@code horizontalSpeed} in
 * blocks per tick; {@code crouching}; {@code limbSwing}/{@code limbSwingAmount} as vanilla passes them.
 */
public record PoseContext(AbstractClientPlayer player, ItemStack mainHand, boolean firstPersonOwner, float partialTick,
        double nowSeconds, float headPitch, boolean airborne, boolean crouching, double horizontalSpeed,
        float limbSwing, float limbSwingAmount) {}
