package dev.csmc.client;

import dev.csmc.CsmcMod;
import dev.csmc.client.render.CachedGunRendering;
import dev.csmc.client.sound.CachedGunSounds;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Dev-only smoke hooks, inert unless their system property is set; results go to the log.
 * <ul>
 *   <li>{@code -Dcsmc.devPreload=ak47,glock}: loads those cached models and resolves their cached sounds once the
 *   initial resource load finishes (no world or input needed).</li>
 *   <li>{@code -Dcsmc.devScreenshot=true}: once in a world, saves first- and third-person screenshots of whatever
 *   is held ({@code screenshots/csmc-dev-*.png}) without any input.</li>
 *   <li>{@code -Dcsmc.devShineCompare=true}: once in a world, saves skin shine on/off screenshots of the held item:
 *   first person and inventory at noon, third person (front), two first-person inspect frames with shine on, and
 *   first person at midnight ({@code csmc-dev-{first,inv,third,night}-{shine,matte}.png},
 *   {@code csmc-dev-inspect-{a,b}.png}); the saved setting is not changed.</li>
 * </ul>
 */
final class DevPreload {
    private static final int SETTLE_TICKS = 100;

    private DevPreload() {}

    static void register() {
        String property = System.getProperty("csmc.devPreload");
        if (property != null && !property.isBlank()) {
            List<String> weapons = Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            boolean[] done = {false};
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                // The loading overlay covers the initial resource reload, which would discard earlier loads.
                if (done[0] || client.getOverlay() != null) return;
                done[0] = true;
                CsmcMod.LOGGER.info("[csmc dev] Preloading cached assets for {}", weapons);
                for (String weapon : weapons) {
                    CachedGunRendering.requestLoad(weapon);
                    CachedGunSounds.devResolve(weapon);
                }
            });
        }
        if (Boolean.getBoolean("csmc.devShineCompare")) registerShineCompare();
        if (Boolean.getBoolean("csmc.devScreenshot")) {
            int[] ticks = {0};
            CameraType[] original = {null};
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.player == null || client.level == null || ticks[0] < 0) return;
                int tick = ++ticks[0];
                if (tick == SETTLE_TICKS) {
                    original[0] = client.options.getCameraType();
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                } else if (tick == SETTLE_TICKS + 20) {
                    shoot(client, "csmc-dev-first.png");
                    client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                } else if (tick == SETTLE_TICKS + 40) {
                    shoot(client, "csmc-dev-third-front.png");
                    client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                } else if (tick == SETTLE_TICKS + 60) {
                    shoot(client, "csmc-dev-third-back.png");
                    client.options.setCameraType(original[0]);
                    ticks[0] = -1;
                }
            });
        }
    }

    private static void registerShineCompare() {
        int[] ticks = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null || ticks[0] < 0) return;
            int tick = ++ticks[0];
            var settings = dev.csmc.client.viewmodel.ViewmodelSettings.get();
            switch (tick - SETTLE_TICKS) {
                case 0 -> {
                    settings.shine = true;
                    dayTime(client, 6000);
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                }
                case 30 -> { shoot(client, "csmc-dev-first-shine.png"); settings.shine = false; }
                case 40 -> {
                    shoot(client, "csmc-dev-first-matte.png");
                    settings.shine = true;
                    client.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
                }
                case 55 -> { shoot(client, "csmc-dev-inv-shine.png"); settings.shine = false; }
                case 65 -> {
                    shoot(client, "csmc-dev-inv-matte.png");
                    client.setScreen(null);
                    settings.shine = true;
                    client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                }
                case 85 -> { shoot(client, "csmc-dev-third-shine.png"); settings.shine = false; }
                case 95 -> {
                    shoot(client, "csmc-dev-third-matte.png");
                    settings.shine = true;
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                }
                case 105 -> CsmcClient.queueInspect();
                case 120 -> shoot(client, "csmc-dev-inspect-a.png");
                case 135 -> shoot(client, "csmc-dev-inspect-b.png");
                case 160 -> dayTime(client, 18000);
                case 200 -> { shoot(client, "csmc-dev-night-shine.png"); settings.shine = false; }
                case 210 -> {
                    shoot(client, "csmc-dev-night-matte.png");
                    settings.shine = true;
                    ticks[0] = -1;
                }
                default -> { }
            }
        });
    }

    private static void dayTime(Minecraft client, long time) {
        var server = client.getSingleplayerServer();
        if (server != null) server.execute(() -> server.overworld().setDayTime(time));
    }

    private static void shoot(Minecraft client, String name) {
        CsmcMod.LOGGER.info("[csmc dev] Holding {}", client.player.getMainHandItem());
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(),
                message -> CsmcMod.LOGGER.info("[csmc dev] {}", message.getString()));
    }
}
