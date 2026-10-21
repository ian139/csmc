package dev.csmc.equipment;

import dev.csmc.item.C4Item;
import dev.csmc.item.HealthshotItem;
import dev.csmc.network.EquipmentActionPayload;
import dev.csmc.network.EquipmentEventPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-owned equipment clocks. Packets can request/cancel, never advance a clock or consume an item. */
public final class EquipmentSystem {
    public static final int DEFUSE_LEASE_TICKS = 10;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Integer> SEQUENCES = new HashMap<>();
    private static final Map<UUID, Restart> RESTARTS = new HashMap<>();
    private record Restart(ServerLevel level, long tick) {}

    private static final class Session {
        final ServerLevel level;
        final long start;
        final InteractionHand hand;
        final ItemStack stack;
        final int slot;
        final BlockPos support;
        final PlantedC4Entity bomb;
        long heartbeat;
        long lastTick = Long.MIN_VALUE;

        Session(ServerPlayer player, InteractionHand hand, BlockHitResult hit, PlantedC4Entity bomb) {
            this.level = player.serverLevel();
            this.start = level.getGameTime();
            this.heartbeat = start;
            this.hand = hand;
            this.stack = player.getItemInHand(hand);
            this.slot = player.getInventory().selected;
            this.support = hit == null ? null : hit.getBlockPos().immutable();
            this.bomb = bomb;
        }
    }

    private EquipmentSystem() {}

    public static void handle(ServerPlayer player, EquipmentActionPayload payload) {
        if (payload.sequence() < 0 || (!payload.action().equals("start") && !payload.action().equals("cancel"))) {
            return;
        }
        Integer previous = SEQUENCES.get(player.getUUID());
        if (previous != null && payload.sequence() <= previous) { return; }
        SEQUENCES.put(player.getUUID(), payload.sequence());
        if (payload.action().equals("cancel")) {
            cancel(player);
        } else if (payload.entityId() >= 0) {
            if (player.serverLevel().getEntity(payload.entityId()) instanceof PlantedC4Entity bomb) {
                startDefuse(player, bomb);
            }
        } else {
            startUse(player, InteractionHand.MAIN_HAND);
        }
    }

    public static boolean startUse(ServerPlayer player, InteractionHand hand) {
        if (!eligible(player)) { return false; }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof HealthshotItem) && !(stack.getItem() instanceof C4Item)) { return false; }
        if (stack.isEmpty() || player.getCooldowns().isOnCooldown(stack.getItem())) { return false; }
        Session existing = SESSIONS.get(player.getUUID());
        if (existing != null && existing.bomb == null && existing.hand == hand && existing.stack == stack
                && existing.level == player.serverLevel() && player.isUsingItem()) {
            return true;
        }
        BlockHitResult hit = stack.getItem() instanceof C4Item ? plantingTarget(player) : null;
        if (stack.getItem() instanceof C4Item && hit == null) { return false; }
        cancel(player);
        if (!canRestart(player)) { return false; }
        player.startUsingItem(hand);
        SESSIONS.put(player.getUUID(), new Session(player, hand, hit, null));
        event(player.serverLevel(), id(stack), "start", player.getId(), player.position(), 0);
        return true;
    }

    /** Server raycast, sturdy UP face and clear space; never trusts a client hit result. */
    public static BlockHitResult plantingTarget(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP
                || !level.getBlockState(hit.getBlockPos()).isFaceSturdy(level, hit.getBlockPos(), Direction.UP)
                || !level.getBlockState(hit.getBlockPos().above()).getCollisionShape(level, hit.getBlockPos().above()).isEmpty()
                || !level.getWorldBorder().isWithinBounds(hit.getBlockPos())
                || !level.mayInteract(player, hit.getBlockPos())) {
            return null;
        }
        return hit;
    }

    public static boolean startDefuse(ServerPlayer player, PlantedC4Entity bomb) {
        if (!eligible(player) || bomb.level() != player.level() || bomb.isRemoved() || bomb.isDefused()
                || !bomb.canDefuse(player)) { return false; }
        Session existing = SESSIONS.get(player.getUUID());
        // A retained session after another player's takeover is a fresh attempt, not a lease renewal.
        if (existing != null && existing.bomb == bomb && player.getUUID().equals(bomb.getDefuserId())) {
            existing.heartbeat = player.serverLevel().getGameTime();
            if (bomb.startDefuse(player)) { return true; }
            cancel(player);
            return false;
        }
        cancel(player);
        if (!canRestart(player)) { return false; }
        Session session = new Session(player, InteractionHand.MAIN_HAND, null, bomb);
        SESSIONS.put(player.getUUID(), session);
        if (!bomb.startDefuse(player)) {
            SESSIONS.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public static boolean isDefusing(ServerPlayer player, PlantedC4Entity bomb) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.bomb == bomb && eligible(player) && session.level == player.serverLevel()
                && session.slot == player.getInventory().selected
                && player.getItemInHand(session.hand) == session.stack
                && session.level.getGameTime() - session.heartbeat <= DEFUSE_LEASE_TICKS;
    }

    public static void tick(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) { return; }
        long now = player.serverLevel().getGameTime();
        if (session.lastTick == now) { return; }
        session.lastTick = now;
        if (session.bomb != null) {
            if (!isDefusing(player, session.bomb) || session.bomb.isRemoved() || session.bomb.isDefused()) {
                cancel(player);
            }
            return;
        }
        if (!eligible(player) || session.level != player.serverLevel() || !player.isUsingItem()
                || player.getUsedItemHand() != session.hand || player.getItemInHand(session.hand) != session.stack
                || player.getUseItem() != session.stack || session.stack.isEmpty()
                || (session.hand == InteractionHand.MAIN_HAND && session.slot != player.getInventory().selected)) {
            cancel(player);
            return;
        }
        BlockHitResult hit = null;
        if (session.support != null) {
            hit = plantingTarget(player);
            if (hit == null || !hit.getBlockPos().equals(session.support)) { cancel(player); return; }
        }
        int duration = session.stack.getItem() instanceof HealthshotItem ? HealthshotItem.USE_TICKS : C4Item.PLANT_TICKS;
        long elapsed = now - session.start;
        if (elapsed < duration) {
            if (elapsed % 2 == 0) {
                event(session.level, id(session.stack), "progress", player.getId(), player.position(),
                        Math.min(1F, elapsed / (float) duration));
            }
            return;
        }
        if (session.stack.getItem() instanceof HealthshotItem) {
            player.heal(10F);
            player.getCooldowns().addCooldown(EquipmentRegistry.HEALTHSHOT, HealthshotItem.COOLDOWN_TICKS);
        } else if (hit == null || PlantedC4Entity.plant(session.level, player, hit.getLocation().add(0, 0.01, 0)) == null) {
            cancel(player);
            return;
        }
        String id = id(session.stack);
        SESSIONS.remove(player.getUUID());
        if (!player.getAbilities().instabuild) { session.stack.shrink(1); }
        player.stopUsingItem();
        event(session.level, id, "complete", player.getId(), player.position(), 1F);
    }

    public static void cancel(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) { return; }
        RESTARTS.put(player.getUUID(), new Restart(player.serverLevel(), player.serverLevel().getGameTime()));
        if (session.bomb != null) {
            session.bomb.cancelDefuse(player);
        } else {
            player.stopUsingItem();
            event(session.level, id(session.stack), "cancel", player.getId(), player.position(), 0);
        }
    }

    public static void forget(ServerPlayer player) {
        cancel(player);
        SEQUENCES.remove(player.getUUID());
        RESTARTS.remove(player.getUUID());
    }

    private static boolean canRestart(ServerPlayer player) {
        Restart restart = RESTARTS.get(player.getUUID());
        return restart == null || restart.level() != player.serverLevel()
                || player.serverLevel().getGameTime() > restart.tick();
    }

    private static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.isRemoved() && !player.hasDisconnected();
    }

    private static String id(ItemStack stack) {
        return stack.getItem() instanceof HealthshotItem ? "healthshot" : "c4";
    }

    public static void event(ServerLevel level, String id, String event, int entityId, Vec3 position, float progress) {
        EquipmentEventPayload payload = new EquipmentEventPayload(id, event, entityId, position.x, position.y,
                position.z, progress);
        for (ServerPlayer observer : level.players()) {
            if (observer.distanceToSqr(position) <= 64 * 64
                    && ServerPlayNetworking.canSend(observer, EquipmentEventPayload.TYPE)) {
                ServerPlayNetworking.send(observer, payload);
            }
        }
    }
    public static void clear() {
        SESSIONS.clear();
        SEQUENCES.clear();
        RESTARTS.clear();
    }

}
