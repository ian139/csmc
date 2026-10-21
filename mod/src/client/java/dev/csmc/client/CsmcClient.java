package dev.csmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.item.GunItem;
import dev.csmc.item.SkinnableWeapon;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.network.GunActionPayload;
import dev.csmc.network.GunRecoilPayload;
import dev.csmc.network.GunInspectPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Client sends held-state trigger intent (PRESS every tick the trigger is held, RELEASE on key-up,
 * inactivity or slot/item change) plus reload requests; cadence, reloads, damage and effects belong
 * to the server, which ignores duplicate PRESS while its own trigger state is armed.
 */
public final class CsmcClient implements ClientModInitializer {
    private static KeyMapping inspect;
    private static boolean inspectQueued;
    private boolean trigger;
    private int selectedSlot = -1;
    private net.minecraft.world.item.Item selectedItem;

    @Override
    public void onInitializeClient() {
        DevPreload.register();
        dev.csmc.client.viewmodel.ViewmodelSettings.register();
        CaseOpeningScreen.register();
        SkinPickerScreen.register();
        KnifeClient.initialize();
        dev.csmc.client.gloves.GloveClient.initialize();
        dev.csmc.client.hud.AmmoHud.initialize();
        dev.csmc.client.equipment.EquipmentClient.initialize();
        ClientPlayNetworking.registerGlobalReceiver(GunRecoilPayload.TYPE, (payload, context) -> {
            Minecraft client = context.client();
            client.execute(() -> {
                if (client.player == null) return;
                client.player.setXRot(Mth.clamp(client.player.getXRot() - payload.pitch(), -90, 90));
                client.player.setYRot(client.player.getYRot() + payload.yaw());
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(GunInspectPayload.TYPE,
                (payload, context) -> CachedGunRendering.inspectEvent(payload));
        // Reuse vanilla's rebindable Attack key; a second mouse-0 mapping would shadow normal mining.
        KeyMapping reload = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.csmc.reload",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.csmc"));
        inspect = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.csmc.inspect",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, "category.csmc"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getConnection() == null) {
                inspectQueued = false;
                trigger = false;
                selectedSlot = -1;
                selectedItem = null;
                return;
            }
            ItemStack current = client.player.getMainHandItem();
            int slot = client.player.getInventory().selected;
            boolean active = current.getItem() instanceof GunItem && client.screen == null;
            // Knives inspect through the same server path; reload and trigger stay gun-only.
            boolean inspectable = current.getItem() instanceof SkinnableWeapon && client.screen == null;
            boolean down = active && client.options.keyAttack.isDown();
            boolean changedStack = slot != selectedSlot || current.getItem() != selectedItem;
            if (changedStack || down) CachedGunRendering.cancelInspect();
            if (trigger && (!down || changedStack)) {
                ClientPlayNetworking.send(new GunActionPayload(GunActionPayload.RELEASE));
                trigger = false;
            }
            // Number-key selection this tick is only synced by vanilla on the next tick; sync it before any
            // gun intent so the server evaluates the stack the player actually holds.
            boolean slotSynced = slot == selectedSlot;
            if (down) {
                // Re-sent every held tick: a same-type hand swap replaces the server stack and clears its
                // trigger without any client-visible slot/item change, so the next PRESS rearms it.
                slotSynced = syncCarriedSlot(client, slot, slotSynced);
                ClientPlayNetworking.send(new GunActionPayload(GunActionPayload.PRESS));
                trigger = true;
            }
            selectedSlot = slot;
            selectedItem = current.getItem();
            while (reload.consumeClick()) {
                if (active) {
                    slotSynced = syncCarriedSlot(client, slot, slotSynced);
                    CachedGunRendering.cancelInspect();
                    ClientPlayNetworking.send(new GunActionPayload(GunActionPayload.RELOAD));
                }
            }
            boolean requestedInspect = inspectQueued;
            inspectQueued = false;
            while (inspect.consumeClick()) requestedInspect = true;
            if (requestedInspect && inspectable && !client.options.keyAttack.isDown() && CachedGunRendering.canInspect()) {
                slotSynced = syncCarriedSlot(client, slot, slotSynced);
                ClientPlayNetworking.send(new GunActionPayload(GunActionPayload.INSPECT));
            } else if (requestedInspect && current.getItem() == EquipmentRegistry.C4 && client.screen == null
                    && !client.player.isUsingItem()) {
                // C4 inspect is cosmetic and local: the server has no equipment inspect state to confirm.
                CachedGunRendering.playAnimation(current, "inspect");
            }
        });
        ScopeZoom.initialize();
    }

    /** Raw click routing avoids the vanilla single-binding map losing F to Swap Offhand after a rebind. */
    public static InputConstants.Key inspectKey() {
        return inspect == null ? null : KeyBindingHelper.getBoundKeyOf(inspect);
    }

    public static void queueInspect() {
        inspectQueued = true;
    }

    private static boolean syncCarriedSlot(Minecraft client, int slot, boolean alreadySynced) {
        if (!alreadySynced) client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        return true;
    }
}
