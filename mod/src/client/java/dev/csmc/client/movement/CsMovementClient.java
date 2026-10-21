package dev.csmc.client.movement;

import com.mojang.blaze3d.platform.InputConstants;
import dev.csmc.movement.CsMovement;
import dev.csmc.movement.MovementStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Client side of CS:GO-style movement: server state, the walk key, local player physics and the dev harness. */
public final class CsMovementClient implements ClientModInitializer {
    private static KeyMapping walkKey;

    @Override
    public void onInitializeClient() {
        // CS +speed (walk, default Shift in CS); Minecraft's Shift is sneak, which CS movement uses as duck.
        walkKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.csmc.walk", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT, "category.csmc"));
        ClientPlayNetworking.registerGlobalReceiver(MovementStatePayload.TYPE, (payload, context) ->
                CsMovement.acceptClientState(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                CsMovement.setClientState(null, CsMovement.DEFAULT_MAX_SPEED));
        ClientTickEvents.END_CLIENT_TICK.register(client -> LocalMovement.endClientTick());
        MovementDevHarness.register();
    }

    /** CS walk ({@code +speed}): ×{@code walkSpeedModifier} max speed while held. */
    public static KeyMapping walkKey() {
        return walkKey;
    }
}
