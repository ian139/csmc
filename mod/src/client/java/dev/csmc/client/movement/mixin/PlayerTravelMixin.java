package dev.csmc.client.movement.mixin;

import dev.csmc.client.movement.LocalMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CS movement replaces vanilla travel for the local player outside hand-off states. */
@Mixin(Player.class)
abstract class PlayerTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void csmc$csTravel(Vec3 input, CallbackInfo callback) {
        if ((Object) this instanceof LocalPlayer player && LocalMovement.travel(player)) callback.cancel();
    }
}
