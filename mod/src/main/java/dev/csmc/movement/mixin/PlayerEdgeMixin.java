package dev.csmc.movement.mixin;

import dev.csmc.movement.CsMovement;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CS ducking does not stop at ledges. Disabled on both sides so the server's replay of a crouching CS player's move
 * ({@code ServerGamePacketListenerImpl.handleMovePlayer}) does not back off where the client walked off.
 */
@Mixin(Player.class)
abstract class PlayerEdgeMixin {
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void csmc$noEdgeBackOffInCsMovement(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> result) {
        if (CsMovement.active((Player) (Object) this)) result.setReturnValue(movement);
    }
}
