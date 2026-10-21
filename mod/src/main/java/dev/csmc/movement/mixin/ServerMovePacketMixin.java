package dev.csmc.movement.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.csmc.movement.CsMovement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * "Moved too quickly" budget of {@code handleMovePlayer} for CS movement players. Vanilla compares the displacement
 * accumulated since the start of the server tick with {@code 100 × packets} (squared blocks) but resets the packet
 * multiplier to 1 above five packets, so legitimate CS speeds arriving bunched in one server tick are rejected.
 * Replaces the non-elytra budget {@code 100} by {@link CsMovement#moveBudget}; unchanged when CS movement is off.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerMovePacketMixin {
    @Shadow
    public ServerPlayer player;
    @Shadow
    private int receivedMovePacketCount;
    @Shadow
    private int knownMovePacketCount;

    @ModifyExpressionValue(method = "handleMovePlayer", at = @At(value = "CONSTANT", args = "floatValue=100.0F"))
    private float csmc$csMovementBudget(float budget) {
        return CsMovement.moveBudget(player, budget, receivedMovePacketCount - knownMovePacketCount);
    }
}
