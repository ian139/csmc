package dev.csmc.thirdperson;

import dev.csmc.grenade.GrenadeThrow;
import dev.csmc.item.GrenadeItem;
import dev.csmc.network.GrenadePrimePayload;
import dev.csmc.network.PlayerActionStatePayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server relay of grenade prime/cancel/throw visuals to tracking players ({@link GrenadePrimeState} decides what is
 * accepted). Primes arrive as untrusted {@link GrenadePrimePayload}s; accepted throws are reported by
 * {@link GrenadeThrow#handle} through {@link #thrown}. A player that starts tracking a primed actor receives the
 * current prime; respawning or changing world silently ends the prime. Never affects gameplay.
 */
public final class GrenadePrime {
    private static final Map<UUID, GrenadePrimeState> STATES = new ConcurrentHashMap<>();
    private static final PendingSnapshots<ServerPlayer> PENDING = new PendingSnapshots<>();
    private static boolean initialized;

    private GrenadePrime() {}

    /** Registers the payload, receiver and lifecycle hooks; later calls do nothing. */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        PayloadTypeRegistry.playC2S().register(GrenadePrimePayload.TYPE, GrenadePrimePayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GrenadePrimePayload.TYPE,
                (payload, context) -> receive(context.player(), payload.primed()));
        ServerTickEvents.END_SERVER_TICK.register(GrenadePrime::tick);
        // Fabric fires START_TRACKING before the entity spawn packet; deliver at tick end so the client knows the id.
        EntityTrackingEvents.START_TRACKING.register((entity, observer) -> {
            if (entity instanceof ServerPlayer actor) PENDING.add(observer, actor.getUUID());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                GrenadePrimeState state = STATES.remove(player.getUUID());
                if (state != null && state.cancel() == GrenadePrimeState.Result.CANCEL) cancel(player);
            }
        });
        // The client resets its grenade input on respawn/world change without a cancel; never keep a stale prime.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> forgetPrime(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> forgetPrime(player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATES.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> reset());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    private static void reset() {
        STATES.clear();
        PENDING.clear();
    }

    /** Ends {@code player}'s prime without a cancel and tells trackers the grenade was thrown at {@code strength}. */
    public static void thrown(ServerPlayer player, GrenadeThrow.Strength strength) {
        GrenadePrimeState state = STATES.get(player.getUUID());
        if (state != null) state.thrown();
        ThirdPersonState.broadcast(player, PlayerActionStatePayload.GRENADE_THROW,
                GrenadePrimeState.throwValue(strength));
    }

    private static void receive(ServerPlayer player, boolean primed) {
        GrenadePrimeState state = STATES.get(player.getUUID());
        if (primed) {
            // Untrusted: only a live, non-spectator grenade holder gets (or keeps growing) server state.
            if (!valid(player)) return;
            if (state == null) STATES.put(player.getUUID(), state = new GrenadePrimeState());
            send(player, state.prime(player.server.getTickCount(), true, player.getInventory().selected,
                    player.getMainHandItem().getItem()));
        } else if (state != null) {
            send(player, state.cancel());
        }
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, GrenadePrimeState>> entries = STATES.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, GrenadePrimeState> entry = entries.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entries.remove();
                continue;
            }
            GrenadePrimeState state = entry.getValue();
            send(player, state.tick(valid(player), player.getInventory().selected,
                    player.getMainHandItem().getItem()));
            if (state.idle(now)) entries.remove();
        }
        // After revalidation, so a prime cancelled this tick is never sent to a new observer.
        PENDING.flush(STATES, (observer, actorId) -> sendPrime(server, observer, actorId));
    }

    private static void sendPrime(MinecraftServer server, ServerPlayer observer, UUID actorId) {
        ServerPlayer actor = server.getPlayerList().getPlayer(actorId);
        if (actor != null && !actor.isRemoved() && !observer.hasDisconnected()
                && PlayerLookup.tracking(actor).contains(observer)
                && ServerPlayNetworking.canSend(observer, PlayerActionStatePayload.TYPE)) {
            ServerPlayNetworking.send(observer,
                    new PlayerActionStatePayload(actor.getId(), PlayerActionStatePayload.GRENADE_PRIME, 0));
        }
    }

    private static void forgetPrime(ServerPlayer player) {
        GrenadePrimeState state = STATES.get(player.getUUID());
        if (state != null) state.forgetPrime();
    }

    private static boolean valid(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && player.getMainHandItem().getItem() instanceof GrenadeItem;
    }

    private static void send(ServerPlayer player, GrenadePrimeState.Result result) {
        switch (result) {
            case PRIME -> ThirdPersonState.broadcast(player, PlayerActionStatePayload.GRENADE_PRIME, 0);
            case CANCEL -> cancel(player);
            case NONE -> {}
        }
    }

    private static void cancel(ServerPlayer player) {
        ThirdPersonState.broadcast(player, PlayerActionStatePayload.GRENADE_CANCEL, 0);
    }

    /**
     * Observer/actor pairs that started tracking since the last flush. A flush hands each pair whose actor is primed
     * right now to the sender, so only the new observer gets the current prime; unprimed actors send nothing.
     */
    static final class PendingSnapshots<T> {
        private final List<T> observers = new ArrayList<>();
        private final List<UUID> actors = new ArrayList<>();

        void add(T observer, UUID actor) {
            observers.add(observer);
            actors.add(actor);
        }

        void flush(Map<UUID, GrenadePrimeState> states, BiConsumer<T, UUID> sendPrime) {
            for (int i = 0; i < observers.size(); i++) {
                GrenadePrimeState state = states.get(actors.get(i));
                if (state != null && state.primed()) sendPrime.accept(observers.get(i), actors.get(i));
            }
            clear();
        }

        void clear() {
            observers.clear();
            actors.clear();
        }
    }
}
