package dev.csmc.client.equipment;

import dev.csmc.client.hud.EquipmentHud;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.client.sound.CachedEquipmentSounds;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.equipment.PlantedC4Entity;
import dev.csmc.item.C4Item;
import dev.csmc.item.HealthshotItem;
import dev.csmc.network.EquipmentActionPayload;
import dev.csmc.network.EquipmentEventPayload;
import dev.csmc.viewmodel.EquipmentUseGate;
import dev.csmc.viewmodel.EquipmentActionHands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Client side of the healthshot, C4 and defuse kit. Presentation only: the server owns every clock, plant, heal and
 * defuse. Held use goes through vanilla {@code Item.use}/release; {@link EquipmentEventPayload} then drives the
 * viewmodel clip ({@link CachedGunRendering#playEquipment}), the clip-marker sounds, the HUD and bomb sounds. A held
 * inject/plant is released explicitly when a screen opens or the window loses focus, since vanilla's release handling
 * does not run then. While use is held on a planted C4 in a focused window this client renews the server's defuse
 * lease with a {@code start} heartbeat every {@value #HEARTBEAT_TICKS} ticks and sends {@code cancel} on release, aim
 * loss, slot change, screen, focus loss or death; a disconnect simply drops the state (the server lease expires). The
 * defuse HUD shows only while the bomb's synchronized defuser is this player.
 */
public final class EquipmentClient {
    private static final int HEARTBEAT_TICKS = 5;
    private static ClientLevel trackedLevel;
    private static int sequence;
    private static int lastSlot = -1;
    private static Item lastItem;
    /** Bomb entity id this client is holding use on, or -1. */
    private static int defuseTarget = -1;
    private static int heartbeatIn;
    /** The hand each actor's inject/plant started in; cancel/complete stop that hand's playback. */
    private static final EquipmentActionHands<InteractionHand, Item> ACTION_HANDS = new EquipmentActionHands<>();

    private EquipmentClient() {}

    public static void initialize() {
        PlantedC4Renderer.register();
        CachedEquipmentSounds.initialize();
        EquipmentHud.initialize();
        ClientPlayNetworking.registerGlobalReceiver(EquipmentEventPayload.TYPE,
                (payload, context) -> context.client().execute(() -> event(context.client(), payload)));
        ClientTickEvents.END_CLIENT_TICK.register(EquipmentClient::tick);
        // The server forgets action sequences on join; start again from zero.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(EquipmentClient::reset));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(EquipmentClient::reset));
    }

    /** The bomb the local player is holding use on and is its synchronized defuser, or null. */
    public static PlantedC4Entity defusingBomb() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (defuseTarget == -1 || level == null || client.player == null) return null;
        return level.getEntity(defuseTarget) instanceof PlantedC4Entity bomb && !bomb.isDefused()
                && EquipmentUseGate.ownsDefuse(client.player.getUUID(), bomb.getDefuserId()) ? bomb : null;
    }

    /** Simple third-person pose while holding or using a healthshot or C4; null keeps vanilla's. */
    public static HumanoidModel.ArmPose armPose(AbstractClientPlayer player, InteractionHand hand) {
        Item item = player.getItemInHand(hand).getItem();
        if (item != EquipmentRegistry.HEALTHSHOT && item != EquipmentRegistry.C4) return null;
        if (player.isUsingItem() && player.getUsedItemHand() == hand) return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
        return item == EquipmentRegistry.C4 ? HumanoidModel.ArmPose.CROSSBOW_HOLD : null;
    }

    private static void reset() {
        CachedEquipmentSounds.clear();
        EquipmentHud.clearUse();
        sequence = 0;
        lastSlot = -1;
        lastItem = null;
        defuseTarget = -1;
        trackedLevel = null;
        ACTION_HANDS.clear();
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.getConnection() == null) {
            if (trackedLevel != null) reset();
            return;
        }
        if (client.level != trackedLevel) {
            // Entity ids and positions belong to one level; the action sequence spans the connection.
            CachedEquipmentSounds.clear();
            EquipmentHud.clearUse();
            defuseTarget = -1;
            trackedLevel = client.level;
            ACTION_HANDS.clear();
        }
        CachedEquipmentSounds.tick();

        ItemStack held = player.getMainHandItem();
        int slot = player.getInventory().selected;
        boolean switched = slot != lastSlot || held.getItem() != lastItem;
        if (switched) {
            // A main-hand switch interrupts only main-hand cues; the server keeps an off-hand inject/plant running
            // across it, so that action's timeline and HUD stay.
            CachedEquipmentSounds.stopTimeline(player.getId(), InteractionHand.MAIN_HAND);
            if (!ACTION_HANDS.running(player.getId(), InteractionHand.OFF_HAND)) EquipmentHud.clearUse();
            String id = CachedGunRendering.equipmentId(held.getItem());
            if ("defuse_kit".equals(id)) {
                CachedEquipmentSounds.play(player.getId(), id, "equip", player.getX(), player.getEyeY(),
                        player.getZ(), CachedEquipmentSounds.HANDLING_RANGE);
            } else if (id != null) {
                CachedEquipmentSounds.timeline(player.getId(), InteractionHand.MAIN_HAND, id, "draw", 0, 0);
            }
            lastSlot = slot;
            lastItem = held.getItem();
        }
        boolean screenOpen = client.screen != null;
        boolean windowActive = client.isWindowActive();
        Item using = player.isUsingItem() ? player.getUseItem().getItem() : null;
        if (EquipmentUseGate.releaseHeldUse(using == EquipmentRegistry.HEALTHSHOT || using == EquipmentRegistry.C4,
                screenOpen, windowActive) && client.gameMode != null) {
            // Sends the release so the server cancels the inject/plant instead of completing it unattended; an
            // unfocused window without a screen still runs vanilla's held-use restart, so the key is released too.
            client.gameMode.releaseUsingItem(player);
            client.options.keyUse.setDown(false);
        }

        PlantedC4Entity aimed = null;
        if (EquipmentUseGate.canHoldDefuse(screenOpen, windowActive, player.isAlive(), player.isSpectator(),
                client.options.keyUse.isDown())
                && client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof PlantedC4Entity bomb
                && !bomb.isDefused() && !bomb.isRemoved()) {
            aimed = bomb;
        }
        if (defuseTarget != -1 && (switched || aimed == null || aimed.getId() != defuseTarget)) {
            ClientPlayNetworking.send(new EquipmentActionPayload("cancel", defuseTarget, sequence++));
            defuseTarget = -1;
        }
        if (aimed != null && defuseTarget == -1) {
            defuseTarget = aimed.getId();
            heartbeatIn = 0;
            // Number-key selection is only synced by vanilla next tick; the server records the defuser's slot.
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
        if (defuseTarget != -1 && --heartbeatIn <= 0) {
            ClientPlayNetworking.send(new EquipmentActionPayload("start", defuseTarget, sequence++));
            heartbeatIn = HEARTBEAT_TICKS;
        }
    }

    private static void event(Minecraft client, EquipmentEventPayload payload) {
        ClientLevel level = client.level;
        String id = payload.id();
        if (level == null || !("healthshot".equals(id) || "c4".equals(id))) return;
        int owner = payload.entityId();
        Entity entity = level.getEntity(owner);
        boolean bomb = switch (payload.event()) {
            case "beep", "disarm_start", "disarm_finish", "explode" -> true;
            case "start", "complete" -> false;
            default -> entity instanceof PlantedC4Entity;
        };
        if (bomb) {
            bombEvent(payload, owner);
            return;
        }
        boolean self = client.player != null && owner == client.player.getId();
        float seconds = ("healthshot".equals(id) ? HealthshotItem.USE_TICKS : C4Item.PLANT_TICKS) / 20f;
        int kind = "healthshot".equals(id) ? EquipmentHud.INJECT : EquipmentHud.PLANT;
        Item item = "healthshot".equals(id) ? EquipmentRegistry.HEALTHSHOT : EquipmentRegistry.C4;
        switch (payload.event()) {
            case "start" -> {
                InteractionHand hand = ACTION_HANDS.start(owner, item, hand(entity, item));
                // An untracked actor's hand is unknown; its cues would follow nothing, so main hand is as good.
                InteractionHand cueHand = hand == null ? InteractionHand.MAIN_HAND : hand;
                CachedEquipmentSounds.stopTimeline(owner, cueHand);
                CachedEquipmentSounds.timeline(owner, cueHand, id, "use", seconds, 0);
                animate(entity, item, hand, seconds, 0);
                if (self) EquipmentHud.use(kind, 0, seconds);
            }
            case "progress" -> {
                animate(entity, item, ACTION_HANDS.progress(owner, item, hand(entity, item)), seconds,
                        payload.progress());
                if (self) EquipmentHud.use(kind, payload.progress(), seconds);
            }
            case "cancel" -> {
                InteractionHand hand = ACTION_HANDS.end(owner, item);
                if (hand == null) CachedEquipmentSounds.stopTimeline(owner);
                else CachedEquipmentSounds.stopTimeline(owner, hand);
                CachedGunRendering.cancelEquipment(owner, hand, item);
                if (self) EquipmentHud.clearUse();
            }
            case "complete" -> {
                CachedEquipmentSounds.play(owner, id, "healthshot".equals(id) ? "success" : "plant",
                        payload.x(), payload.y(), payload.z(), CachedEquipmentSounds.HANDLING_RANGE);
                CachedGunRendering.cancelEquipment(owner, ACTION_HANDS.end(owner, item), item);
                if (self) EquipmentHud.clearUse();
            }
            default -> { }
        }
    }

    private static void bombEvent(EquipmentEventPayload payload, int bomb) {
        double x = payload.x(), y = payload.y(), z = payload.z();
        // Bomb-wide events never confirm whose defuse it is; defusingBomb() reads the synchronized defuser.
        switch (payload.event()) {
            case "beep" -> {
                PlantedC4Renderer.beep(bomb);
                CachedEquipmentSounds.play(bomb, "c4", "beep", x, y, z, CachedEquipmentSounds.BEEP_RANGE);
            }
            case "disarm_start" ->
                    CachedEquipmentSounds.play(bomb, "c4", "disarm_start", x, y, z, CachedEquipmentSounds.HANDLING_RANGE);
            case "cancel" -> CachedEquipmentSounds.stop(bomb);
            case "disarm_finish" -> {
                CachedEquipmentSounds.stop(bomb);
                CachedEquipmentSounds.play(bomb, "c4", "disarm_finish", x, y, z, CachedEquipmentSounds.HANDLING_RANGE);
                if (bomb == defuseTarget) defuseTarget = -1;
            }
            case "explode" -> {
                CachedEquipmentSounds.stop(bomb);
                CachedEquipmentSounds.play(bomb, "c4", "explode", x, y, z, CachedEquipmentSounds.EXPLODE_RANGE);
                if (bomb == defuseTarget) defuseTarget = -1;
            }
            default -> { }
        }
    }

    /**
     * Rebases the actor's clip in its action's start hand to the server's progress (local and remote players alike).
     * Once that hand no longer holds the item, its retained stack keeps the running clip; it never moves to the other
     * hand.
     */
    private static void animate(Entity entity, Item item, InteractionHand hand, float seconds, float progress) {
        if (!(entity instanceof Player player) || hand == null) return;
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() == item) CachedGunRendering.playEquipment(stack, player.getId(), hand, "use", seconds, progress);
    }

    /** Hand discovery for an action's first event only; null when the actor is untracked or holds no such item. */
    private static InteractionHand hand(Entity entity, Item item) {
        if (!(entity instanceof Player player)) return null;
        if (player.isUsingItem() && player.getUseItem().getItem() == item) return player.getUsedItemHand();
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() == item) return hand;
        }
        return null;
    }
}
