package dev.csmc.client.gloves;

import dev.csmc.gloves.GloveSlot;
import dev.csmc.network.GloveSyncPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Client side of the glove slot. Every {@link GloveSyncPayload} (item plus finish only) is kept as a render snapshot
 * per entity id, because the server sends tracking data before the entity spawns on this client. An EMPTY snapshot
 * is authoritative (no glove); an absent entry means no snapshot was received. Snapshots are client-only: they are
 * never written into the menu slot or {@link GloveSlot#ATTACHMENT}, whose stack the inventory syncs itself with every
 * component. The first-person arms use the local player's snapshot (falling back to the menu-synced attachment only
 * before one arrives); other players' snapshots are kept for future third-person use. A snapshot is dropped when its
 * entity unloads (the server resends on tracking) or the session ends.
 */
public final class GloveClient {
    private static final Int2ObjectMap<ItemStack> SNAPSHOTS = new Int2ObjectOpenHashMap<>();
    private static final Component SLOT_NAME = Component.translatableWithFallback("slot.csmc.gloves", "Gloves");
    private static final int DARK = 0xFF373737;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int FILL = 0xFF8B8B8B;

    private GloveClient() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(GloveSyncPayload.TYPE,
                (payload, context) -> SNAPSHOTS.put(payload.entityId(), payload.glove()));
        // The local player's entity is replaced (same id) on respawn: its snapshot outlives the old entity.
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (!(entity instanceof LocalPlayer)) SNAPSHOTS.remove(entity.getId());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SNAPSHOTS::clear));
    }

    /** The local player's worn glove for rendering (EMPTY when none): its snapshot, else the menu-synced attachment. */
    public static ItemStack localGloveStack() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        ItemStack snapshot = SNAPSHOTS.get(player.getId());
        return snapshot != null ? snapshot : GloveSlot.get(player);
    }

    /** Another player's worn glove as last synced, or EMPTY. Kept for third-person rendering. */
    public static ItemStack remoteGlove(int entityId) {
        return SNAPSHOTS.getOrDefault(entityId, ItemStack.EMPTY);
    }

    /** The glove slot, or a wrapper around it (creative's survival tab re-indexes wrappers over the same container). */
    public static boolean isGloveSlot(Slot slot) {
        return slot != null && slot.container instanceof GloveSlot.GloveContainer;
    }

    /**
     * Draws a vanilla-style 18 px slot frame around the 16 px item cell at ({@code x}, {@code y}): dark top/left,
     * white bottom/right, grey corners and fill, matching the slots baked into container backgrounds.
     */
    public static void drawFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 16, y + 16, FILL);
        graphics.fill(x - 1, y - 1, x + 16, y, DARK);
        graphics.fill(x - 1, y, x, y + 16, DARK);
        graphics.fill(x, y + 16, x + 17, y + 17, LIGHT);
        graphics.fill(x + 16, y, x + 17, y + 16, LIGHT);
        graphics.fill(x + 16, y - 1, x + 17, y, FILL);
        graphics.fill(x - 1, y + 16, x, y + 17, FILL);
    }

    /** Names the empty glove slot under the cursor, as vanilla does for no item tooltip. */
    public static void renderEmptyTooltip(GuiGraphics graphics, Font font, Slot hovered, ItemStack carried,
                                          int mouseX, int mouseY) {
        if (isGloveSlot(hovered) && !hovered.hasItem() && carried.isEmpty()) {
            graphics.renderTooltip(font, SLOT_NAME, mouseX, mouseY);
        }
    }
}
