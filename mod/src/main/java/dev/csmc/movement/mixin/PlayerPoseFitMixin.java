package dev.csmc.movement.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.csmc.movement.geometry.AnalyticClearance;
import dev.csmc.movement.geometry.AnalyticCollision;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pose changes check room with vanilla shapes, which omit movement geometry for analytic players. Both client and
 * server must retain the crouching pose and dimensions when there is no room to stand under analytic geometry.
 */
@Mixin(Player.class)
abstract class PlayerPoseFitMixin {
    @ModifyReturnValue(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("RETURN"))
    private boolean csmc$fitsMovementGeometry(boolean fits, Pose pose) {
        Player self = (Player) (Object) this;
        if (!fits || !AnalyticCollision.appliesTo(self)) return fits;
        return AnalyticClearance.clear(self.level(),
                self.getDimensions(pose).makeBoundingBox(self.position()).deflate(1.0E-7));
    }
}
