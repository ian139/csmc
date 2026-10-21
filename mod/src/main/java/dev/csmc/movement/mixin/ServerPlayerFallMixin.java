package dev.csmc.movement.mixin;

import dev.csmc.movement.MovementContact;
import dev.csmc.movement.geometry.AnalyticCollision;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server fall-damage rule for analytic (CS movement) players: while airborne but resting on or sliding along an
 * upward-facing movement solid such as a surf ramp ({@link MovementContact#supportedByGeometry}), fall distance is
 * cleared instead of accumulated, so surfing down a ramp never causes fall damage. Passing beside walls, under
 * overhangs or just above a surface keeps accumulating, so a later landing still deals the fall's damage. Landings
 * reported on ground run vanilla unchanged.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerFallMixin {
    @Inject(method = "doCheckFallDamage", at = @At("HEAD"), cancellable = true)
    private void csmc$noFallWhileOnMovementGeometry(double dx, double dy, double dz, boolean onGround,
            CallbackInfo callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!onGround && AnalyticCollision.appliesTo(player) && MovementContact.supportedByGeometry(player)) {
            player.resetFallDistance();
            callback.cancel();
        }
    }
}
