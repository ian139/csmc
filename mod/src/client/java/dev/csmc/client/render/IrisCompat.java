package dev.csmc.client.render;

import dev.csmc.skin.ShaderPackProbe;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional Iris soft dependency. Iris replaces custom core shaders while a shader pack is active, so the shine layer
 * would draw nothing there; callers use the matte cutout layer instead. Read through Iris's public API by reflection
 * (no compile or runtime dependency) and sampled once per client tick, so frames only read a field.
 */
final class IrisCompat {
    private static BooleanSupplier probe;
    private static volatile boolean shaderPackInUse;
    private static boolean registered;

    private IrisCompat() {
    }

    static synchronized void register() {
        if (registered) return;
        registered = true;
        if (!FabricLoader.getInstance().isModLoaded("iris")) return;
        probe = ShaderPackProbe.of(ShaderPackProbe.IRIS_API);
        // Iris present but its API unreadable: stay on the shader-pack-safe matte path.
        shaderPackInUse = probe == null || probe.getAsBoolean();
        if (probe != null) ClientTickEvents.START_CLIENT_TICK.register(client -> shaderPackInUse = probe.getAsBoolean());
    }

    /** Whether an Iris shader pack was active at the start of this tick; false without Iris. */
    static boolean shaderPackInUse() {
        return shaderPackInUse;
    }
}
