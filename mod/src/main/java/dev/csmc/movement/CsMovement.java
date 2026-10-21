package dev.csmc.movement;

import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.item.GrenadeItem;
import dev.csmc.item.GunItem;
import dev.csmc.movement.geometry.AnalyticCollision;
import dev.csmc.movement.physics.CsMovementParams;
import dev.csmc.movement.physics.CsUnits;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.gamerule.v1.rule.EnumRule;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * CS:GO-style movement: the {@code csmcMovement} gamerule, owner synchronisation of the movement variables and the
 * held weapon's max speed, and the shared predicates other systems query.
 *
 * <p>The server never simulates CS movement: like vanilla it replays the client's reported positions. Analytic
 * {@code MovementGeometry} solids are therefore never applied to players on the server (their vanilla shapes are
 * empty for analytic players); only the client traces them.
 */
public final class CsMovement implements ModInitializer {
    /** CS:GO unarmed/knife {@code max_player_speed} (items_game.txt knife prefab, SteamTracking dump), units/s. */
    public static final double DEFAULT_MAX_SPEED = 250.0;
    /** CS:GO grenade {@code max_player_speed} (items_game.txt grenade prefabs, SteamTracking dump), units/s. */
    public static final double GRENADE_MAX_SPEED = 245.0;
    /**
     * Vanilla walking speed (4.317 blocks/s, the Minecraft wiki's measured value for attribute speed 0.1) in blocks
     * per tick. Ballistics maps CS {@code max_speed} 250 to this speed; with CS movement on, 250 u/s is reached at
     * {@link CsUnits} scale instead.
     */
    private static final double MC_WALK_BLOCKS_PER_TICK = 4.317 / 20.0;
    private static final double CS_RUN_SPEED = 250.0;

    public static final GameRules.Key<EnumRule<MovementMode>> RULE = GameRuleRegistry.register("csmcMovement",
            GameRules.Category.PLAYER, GameRuleFactory.createEnumRule(MovementMode.OFF, CsMovement::onRuleChanged));

    /** Last state sent to each player; a missing entry forces the next sync to send. Server thread only. */
    private static final Map<UUID, MovementStatePayload> SENT = new HashMap<>();
    /**
     * Prefix of an entity tag that overrides the world's {@code csmcMovement} for one player, for example
     * {@code csmc_movement_bhop}; the suffix is a {@link MovementMode} name ({@code off}, {@code competitive},
     * {@code bhop}, {@code surf}). Datapacks use it to give lobbies and arenas different movement. Unknown suffixes
     * are ignored; with several valid tags, the first in {@link MovementMode} order wins.
     */
    public static final String OVERRIDE_TAG_PREFIX = "csmc_movement_";
    private static final MovementMode[] MODES = MovementMode.values();

    @Nullable
    private static volatile CsMovementParams clientParams;
    private static volatile double clientHeldMaxSpeed = DEFAULT_MAX_SPEED;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(MovementStatePayload.TYPE, MovementStatePayload.STREAM_CODEC);
        AnalyticCollision.setPredicate(entity -> entity instanceof Player player && !player.isSpectator()
                && active(player));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> resync(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.player.getUUID()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> resync(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                resync(player));
        ServerTickEvents.END_SERVER_TICK.register(CsMovement::syncAll);
        MovementDevLog.register();
    }

    /** The world's {@code csmcMovement} value. Server-side levels only; the client never reads its own rules. */
    public static MovementMode mode(Level level) {
        return level.getGameRules().getRule(RULE).get();
    }

    /** The mode for {@code player} on the server: an {@link #OVERRIDE_TAG_PREFIX} tag if present, else the world's. */
    public static MovementMode mode(Player player) {
        var tags = player.getTags();
        if (!tags.isEmpty()) {
            for (MovementMode mode : MODES) {
                if (tags.contains(OVERRIDE_TAG_PREFIX + mode)) return mode;
            }
        }
        return mode(player.level());
    }

    /**
     * Movement variables in effect for {@code player}, or null when CS movement is off. On the server this is the
     * player's override tag or the gamerule of the player's world; on the client it is the last
     * {@link MovementStatePayload} received.
     */
    @Nullable
    public static CsMovementParams params(Player player) {
        return player.level().isClientSide ? clientParams : mode(player).params();
    }

    /** Whether CS movement rules apply to {@code player} (see {@link #params}). */
    public static boolean active(Player player) {
        return params(player) != null;
    }

    /**
     * Factor applied to the measured horizontal speed before Ballistics' movement-inaccuracy ramp. That ramp maps CS
     * {@code max_speed} 250 to vanilla walking speed; with CS movement on, players run at real CS speed
     * ({@code 250 u/s} at {@link CsUnits} scale), so the measured speed is rescaled to the ramp's frame:
     * {@code (4.317 / 20) / unitsPerSecondToBlocksPerTick(250)} ≈ 0.6907. 1.0 when CS movement is off.
     */
    public static double inaccuracySpeedScale(ServerPlayer player) {
        return active(player) ? MC_WALK_BLOCKS_PER_TICK / CsUnits.unitsPerSecondToBlocksPerTick(CS_RUN_SPEED) : 1.0;
    }

    /**
     * Most move packets per server tick credited by {@link #moveBudget}: half a second of client ticks arriving
     * bunched (network jitter, a server hitch). More packets in one tick get no further allowance.
     */
    public static final int MAX_BUNCHED_PACKETS = 10;
    /** Per-packet slack in blocks beyond the preset speed limit (rounding, one step-up, duck shift). */
    private static final double PACKET_SLACK = 0.75;

    /**
     * Squared-displacement budget per packet multiplier for vanilla's "moved too quickly" check
     * ({@code ServerGamePacketListenerImpl.handleMovePlayer}: reject when {@code dist² - velocity² > budget × q},
     * with {@code q} the packets received since the server tick began, reset to 1 above five). With CS movement on,
     * one client tick can move at most {@code √3 × sv_maxvelocity} (Source clamps each axis) plus
     * {@link #PACKET_SLACK}, so {@code packets} bunched packets (capped at {@link #MAX_BUNCHED_PACKETS}) may
     * legitimately add up to {@code packets ×} that; the budget is raised just enough for vanilla's {@code q} to
     * admit it. Never lower than {@code vanillaBudget}; exactly {@code vanillaBudget} when CS movement is off.
     */
    public static float moveBudget(ServerPlayer player, float vanillaBudget, int packets) {
        CsMovementParams params = params(player);
        if (params == null) return vanillaBudget;
        int counted = Math.max(1, Math.min(packets, MAX_BUNCHED_PACKETS));
        int vanillaMultiplier = packets > 5 ? 1 : Math.max(1, packets);
        double perPacket = Math.sqrt(3.0) * CsUnits.unitsPerSecondToBlocksPerTick(params.maxVelocity()) + PACKET_SLACK;
        double allowed = counted * perPacket;
        return (float) Math.max(vanillaBudget, allowed * allowed / vanillaMultiplier);
    }

    /**
     * CS max ground speed of the main-hand item before attribute effects, units/s: a gun's unscoped
     * {@code max_speed} from its weapon definition, 245 for grenades and 250 for knives, empty hands and everything
     * else (CS:GO {@code max_player_speed} values from items_game.txt).
     */
    public static double heldMaxSpeed(Player player) {
        Item item = player.getMainHandItem().getItem();
        if (item instanceof GunItem gun) {
            WeaponData data = WeaponRegistry.get(gun.weaponId());
            return data != null ? data.maxSpeed() : DEFAULT_MAX_SPEED;
        }
        return item instanceof GrenadeItem ? GRENADE_MAX_SPEED : DEFAULT_MAX_SPEED;
    }

    /** Client-side receipt of {@link MovementStatePayload}; null params turn CS movement off locally. */
    public static void setClientState(@Nullable CsMovementParams params, double heldMaxSpeed) {
        clientParams = params;
        clientHeldMaxSpeed = heldMaxSpeed;
    }

    /**
     * Applies a received {@link MovementStatePayload} unless {@link MovementStatePayload#validationError} rejects
     * it; a rejected payload is logged and the previous client state is kept. Returns whether it was applied.
     */
    public static boolean acceptClientState(MovementStatePayload payload) {
        String error = payload.validationError();
        if (error != null) {
            CsmcMod.LOGGER.warn("Ignoring invalid csmc:movement_state payload ({}); keeping previous movement state",
                    error);
            return false;
        }
        setClientState(payload.params(), payload.heldMaxSpeed());
        return true;
    }

    /** Movement variables from the last accepted {@link MovementStatePayload}, null when off. */
    @Nullable
    public static CsMovementParams clientParams() {
        return clientParams;
    }

    /** Held max speed from the last {@link MovementStatePayload}, units/s. */
    public static double clientHeldMaxSpeed() {
        return clientHeldMaxSpeed;
    }

    private static void onRuleChanged(MinecraftServer server, EnumRule<MovementMode> rule) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sync(player);
    }

    private static void resync(ServerPlayer player) {
        SENT.remove(player.getUUID());
        sync(player);
    }

    private static void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sync(player);
    }

    /** Sends the player's state when it differs from what the player last received. */
    private static void sync(ServerPlayer player) {
        CsMovementParams params = params(player);
        double speed = heldMaxSpeed(player);
        MovementStatePayload sent = SENT.get(player.getUUID());
        if (sent != null && Objects.equals(sent.params(), params) && sent.heldMaxSpeed() == speed) return;
        // Mock players and clients without the mod never negotiate the channel; retry each tick until they do.
        if (!ServerPlayNetworking.canSend(player, MovementStatePayload.TYPE)) return;
        MovementStatePayload payload = new MovementStatePayload(params, speed);
        ServerPlayNetworking.send(player, payload);
        SENT.put(player.getUUID(), payload);
    }
}
