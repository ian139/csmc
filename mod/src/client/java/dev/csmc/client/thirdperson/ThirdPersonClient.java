package dev.csmc.client.thirdperson;

import dev.csmc.network.PlayerActionStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Third-person CS poses: timeline lifecycle and the remote action-state receiver. Providers register here too. */
public final class ThirdPersonClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ActionTimeline::clear));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> ActionTimeline.forget(entity.getId()));
        ClientPlayNetworking.registerGlobalReceiver(PlayerActionStatePayload.TYPE,
                (payload, context) -> PlayerActionStates.receive(payload));
        dev.csmc.client.thirdperson.blend.BlendClient.initialize();
        dev.csmc.client.thirdperson.guns.GunPoses.initialize();
        dev.csmc.client.thirdperson.melee.MeleeAnimations.initialize();
    }
}
