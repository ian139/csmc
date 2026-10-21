package dev.csmc.thirdperson;

import dev.csmc.network.PlayerActionStatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Server side of third-person action state: registers {@link PlayerActionStatePayload} and broadcasts to trackers. */
public final class ThirdPersonState implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(PlayerActionStatePayload.TYPE, PlayerActionStatePayload.STREAM_CODEC);
        ScopeTracker.initialize();
        GrenadePrime.initialize();
    }

    /** Sends {@code kind/value} for {@code actor} to every player tracking it (not the actor itself). */
    public static void broadcast(ServerPlayer actor, int kind, int value) {
        PlayerActionStatePayload payload = new PlayerActionStatePayload(actor.getId(), kind, value);
        for (ServerPlayer tracker : PlayerLookup.tracking(actor)) {
            if (tracker != actor && ServerPlayNetworking.canSend(tracker, PlayerActionStatePayload.TYPE)) {
                ServerPlayNetworking.send(tracker, payload);
            }
        }
    }
}
