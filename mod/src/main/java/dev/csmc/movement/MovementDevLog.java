package dev.csmc.movement;

import dev.csmc.CsmcMod;
import java.util.Locale;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Dev-only server log, inert unless {@code -Dcsmc.devMovementServerLog=true}: every server tick, each player's
 * accepted position, reported ground state, fall distance and movement mode, prefixed {@code [csmc move] server}.
 */
final class MovementDevLog {
    private MovementDevLog() {}

    static void register() {
        if (!Boolean.getBoolean("csmc.devMovementServerLog")) return;
        CsmcMod.LOGGER.info("[csmc move] server per-tick position log enabled");
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CsmcMod.LOGGER.info(String.format(Locale.ROOT,
                        "[csmc move] server gt=%d name=%s pos=%.5f,%.5f,%.5f onGround=%b fall=%.3f mode=%s held=%.1f",
                        player.serverLevel().getGameTime(), player.getGameProfile().getName(), player.getX(),
                        player.getY(), player.getZ(), player.onGround(), player.fallDistance,
                        CsMovement.mode(player), CsMovement.heldMaxSpeed(player)));
            }
        });
    }
}
