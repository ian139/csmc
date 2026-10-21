package dev.csmc.client.movement.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.csmc.client.movement.LocalMovement;
import dev.csmc.movement.geometry.AnalyticCollision;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client {@code Entity.move} hooks: while CS movement commits a move it substitutes its own collision result and
 * ground state; otherwise an analytic local player (hand-off states) is additionally clipped against movement
 * geometry that its vanilla collision shapes omit.
 */
@Mixin(Entity.class)
abstract class EntityMoveMixin {
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 csmc$csCollide(Entity self, Vec3 movement, Operation<Vec3> original) {
        Vec3 committed = LocalMovement.committedMove(self);
        if (committed != null) return committed;
        Vec3 collided = original.call(self, movement);
        if (self instanceof LocalPlayer player && AnalyticCollision.appliesTo(player)) {
            return LocalMovement.clipHandOff(player, collided);
        }
        return collided;
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V"))
    private void csmc$csGround(Entity self, boolean onGround, Vec3 movement, Operation<Void> original) {
        boolean ground = LocalMovement.committedMove(self) != null ? LocalMovement.committedGrounded() : onGround;
        original.call(self, ground, movement);
    }
}
