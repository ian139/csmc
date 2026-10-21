package dev.csmc.client.surf;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

/** Client side of surf ramps: block models. */
public final class SurfClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModelLoadingPlugin.register(context -> context.resolveModel().register(resolver -> {
            var id = resolver.id();
            return id.equals(SurfRampModel.BLOCK_MODEL) || id.equals(SurfRampModel.ITEM_MODEL)
                    ? new SurfRampModel() : null;
        }));
        SurfDevShots.register();
    }
}
