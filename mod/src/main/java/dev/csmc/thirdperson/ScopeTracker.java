package dev.csmc.thirdperson;

import dev.csmc.network.PlayerActionStatePayload;
import dev.csmc.server.GunSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Publishes each player's server-side gun zoom ({@link GunSystem#scopeLevel}, read-only) as
 * {@link PlayerActionStatePayload#SCOPE}: changes go to every tracker and to the actor itself (its F5 body pose),
 * and a player that starts tracking an actor receives the actor's current level. Presentation only.
 */
public final class ScopeTracker {
    private static final Levels LEVELS = new Levels();
    /** Tracker/actor pairs that started tracking this tick; flushed at tick end, after the spawn packets. */
    private static final List<ServerPlayer[]> PENDING = new ArrayList<>();

    private ScopeTracker() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(ScopeTracker::tick);
        // Fabric fires START_TRACKING before the entity spawn packet; deliver at tick end so the client knows the id.
        EntityTrackingEvents.START_TRACKING.register((entity, tracker) -> {
            if (entity instanceof ServerPlayer actor) PENDING.add(new ServerPlayer[]{tracker, actor});
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PENDING.add(new ServerPlayer[]{handler.player, handler.player}));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LEVELS.forget(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> reset());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    private static void reset() {
        LEVELS.clear();
        PENDING.clear();
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer actor : server.getPlayerList().getPlayers()) {
            int level = GunSystem.scopeLevel(actor);
            if (LEVELS.update(actor.getUUID(), level)) {
                ThirdPersonState.broadcast(actor, PlayerActionStatePayload.SCOPE, level);
                send(actor, actor, level);
            }
        }
        for (ServerPlayer[] pair : PENDING) {
            ServerPlayer tracker = pair[0];
            ServerPlayer actor = pair[1];
            if (!tracker.hasDisconnected() && !actor.isRemoved()
                    && (tracker == actor || PlayerLookup.tracking(actor).contains(tracker))) {
                send(tracker, actor, GunSystem.scopeLevel(actor));
            }
        }
        PENDING.clear();
    }

    private static void send(ServerPlayer recipient, ServerPlayer actor, int level) {
        if (ServerPlayNetworking.canSend(recipient, PlayerActionStatePayload.TYPE)) {
            ServerPlayNetworking.send(recipient, new PlayerActionStatePayload(actor.getId(), PlayerActionStatePayload.SCOPE, level));
        }
    }

    /** Last published level per player; absent means 0, the client's default for a newly seen player. */
    static final class Levels {
        private final Map<UUID, Integer> scoped = new HashMap<>();

        /** Records {@code level} for {@code player}; true when it differs from the last published level. */
        boolean update(UUID player, int level) {
            Integer previous = level == 0 ? scoped.remove(player) : scoped.put(player, level);
            return (previous == null ? 0 : previous) != level;
        }

        void forget(UUID player) {
            scoped.remove(player);
        }

        void clear() {
            scoped.clear();
        }
    }
}
