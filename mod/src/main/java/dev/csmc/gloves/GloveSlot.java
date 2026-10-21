package dev.csmc.gloves;

import com.mojang.datafixers.util.Pair;
import dev.csmc.CsmcMod;
import dev.csmc.item.GloveItem;
import dev.csmc.network.GloveSyncPayload;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

/**
 * The worn-glove slot: one {@link GloveItem} per player, stored in the persistent {@link #ATTACHMENT} and shown in the
 * survival inventory as menu slot {@link #MENU_INDEX} at ({@link #SLOT_X}, {@link #SLOT_Y}), directly above the
 * offhand slot. Dropped on death like armour unless {@code keepInventory}; kept across respawn and dimension changes.
 */
public final class GloveSlot {
    /** The worn glove, or {@link ItemStack#EMPTY}. Persistent; copied to the respawned player. */
    public static final AttachmentType<ItemStack> ATTACHMENT = AttachmentRegistry.<ItemStack>builder()
            .persistent(ItemStack.OPTIONAL_CODEC)
            .initializer(() -> ItemStack.EMPTY)
            .copyOnDeath()
            .buildAndRegister(CsmcMod.id("glove"));
    /** Index of the glove slot in {@code InventoryMenu}: right after the offhand slot (45). */
    public static final int MENU_INDEX = 46;
    /** Slot position in the inventory screen: directly above the offhand slot at (77, 62). */
    public static final int SLOT_X = 77;
    public static final int SLOT_Y = 44;
    /** Empty-slot icon sprite (block atlas). */
    public static final ResourceLocation EMPTY_SPRITE = CsmcMod.id("item/empty_glove_slot");

    private GloveSlot() {}

    public static ItemStack get(Player player) {
        return player.getAttachedOrElse(ATTACHMENT, ItemStack.EMPTY);
    }

    /** Wears {@code glove} (empty to take it off) and, on the server, broadcasts the change. */
    public static void set(Player player, ItemStack glove) {
        if (glove.isEmpty()) player.removeAttached(ATTACHMENT);
        else player.setAttached(ATTACHMENT, glove);
        if (player instanceof ServerPlayer serverPlayer) broadcast(serverPlayer);
    }

    public static boolean accepts(ItemStack stack) {
        return stack.getItem() instanceof GloveItem;
    }

    public static GloveSyncPayload payload(Player player) {
        return new GloveSyncPayload(player.getId(), get(player).copy());
    }

    /** Sends {@code player}'s glove to themself and every player tracking them. */
    public static void broadcast(ServerPlayer player) {
        GloveSyncPayload payload = payload(player);
        send(player, payload);
        for (ServerPlayer tracker : PlayerLookup.tracking(player)) {
            if (tracker != player) send(tracker, payload);
        }
    }

    private static void send(ServerPlayer to, GloveSyncPayload payload) {
        if (to.connection != null && ServerPlayNetworking.canSend(to, GloveSyncPayload.TYPE)) {
            ServerPlayNetworking.send(to, payload);
        }
    }

    /**
     * Drops the worn glove at death as armour is: not with {@code keepInventory}, nor for a spectator (vanilla keeps
     * a spectator's inventory). The cleared slot is broadcast.
     */
    public static void dropOnDeath(ServerPlayer player) {
        if (player.isSpectator() || player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        ItemStack glove = get(player);
        if (glove.isEmpty()) return;
        set(player, ItemStack.EMPTY);
        player.drop(glove, true, false);
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(GloveSyncPayload.TYPE, GloveSyncPayload.STREAM_CODEC);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) dropOnDeath(player);
        });
        // After the default phase, where Fabric copies attachments to the new player.
        ResourceLocation afterCopy = CsmcMod.id("glove_after_attachment_copy");
        ServerPlayerEvents.AFTER_RESPAWN.addPhaseOrdering(Event.DEFAULT_PHASE, afterCopy);
        ServerPlayerEvents.AFTER_RESPAWN.register(afterCopy, (oldPlayer, newPlayer, alive) -> broadcast(newPlayer));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> broadcast(handler.player));
        EntityTrackingEvents.START_TRACKING.register((tracked, tracker) -> {
            if (tracked instanceof Player player) send(tracker, payload(player));
        });
    }

    /** A one-slot container view over a player's {@link #ATTACHMENT}. */
    public static final class GloveContainer implements Container {
        private final Player player;

        public GloveContainer(Player player) {
            this.player = player;
        }

        @Override public int getContainerSize() { return 1; }
        @Override public boolean isEmpty() { return get(player).isEmpty(); }
        @Override public ItemStack getItem(int slot) { return slot == 0 ? get(player) : ItemStack.EMPTY; }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack worn = getItem(slot);
            if (worn.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            ItemStack taken = worn.split(amount);
            set(player, worn);
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack worn = getItem(slot);
            if (slot == 0) player.removeAttached(ATTACHMENT);
            return worn;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot == 0) set(player, stack);
        }

        @Override public int getMaxStackSize() { return 1; }
        @Override public void setChanged() { if (player instanceof ServerPlayer serverPlayer) broadcast(serverPlayer); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return accepts(stack); }
        @Override public void clearContent() { set(player, ItemStack.EMPTY); }
    }

    /** The inventory-menu slot: accepts only gloves, one at a time. */
    public static final class GloveMenuSlot extends Slot {
        public GloveMenuSlot(Player player) {
            super(new GloveContainer(player), 0, SLOT_X, SLOT_Y);
        }

        @Override public boolean mayPlace(ItemStack stack) { return accepts(stack); }
        @Override public int getMaxStackSize() { return 1; }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, EMPTY_SPRITE);
        }
    }
}
