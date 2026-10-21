package dev.csmc.client.thirdperson.blend;

import dev.csmc.client.thirdperson.ThirdPersonPoseApplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Registers render-state lifetime independently of the action timeline. */
public final class BlendClient {
    private BlendClient() {}

    public static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ThirdPersonPoseApplier::clear));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> ThirdPersonPoseApplier.forget(entity.getId()));
        dev.csmc.client.thirdperson.dev.ThirdPersonDevHarness.initialize();
    }
}
