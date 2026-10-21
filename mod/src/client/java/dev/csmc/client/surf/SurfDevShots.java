package dev.csmc.client.surf;

import dev.csmc.CsmcMod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Dev-only surf ramp screenshot hook, inert unless {@code -Dcsmc.devSurfShots} is set; results go to the log with
 * the prefix {@code [csmc surf dev]}.
 * <ul>
 *   <li>{@code -Dcsmc.devSurfCommands=cmd|cmd}: once in a world, the player sends these commands (no leading slash);
 *   the player needs permission level 2 for {@code /csmc surf}.</li>
 *   <li>{@code -Dcsmc.devSurfShots=name@x,y,z,yaw,pitch|...}: afterwards teleports to each view with {@code /tp},
 *   hides the HUD and saves {@code screenshots/<name>.png} in the game directory.</li>
 * </ul>
 */
final class SurfDevShots {
    private static final int SETTLE_TICKS = 100;
    private static final int VIEW_TICKS = 80;

    private record View(String name, String tp) {}

    private SurfDevShots() {}

    static void register() {
        String shots = System.getProperty("csmc.devSurfShots");
        if (shots == null || shots.isBlank()) return;
        List<String> commands = split(System.getProperty("csmc.devSurfCommands", ""));
        List<View> views = new ArrayList<>();
        for (String spec : split(shots)) {
            int at = spec.indexOf('@');
            String[] v = spec.substring(at + 1).split(",");
            views.add(new View(spec.substring(0, at), String.format(Locale.ROOT, "tp @s %s %s %s %s %s",
                    v[0], v[1], v[2], v[3], v[4])));
        }
        int[] ticks = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null || ticks[0] < 0) return;
            int tick = ++ticks[0];
            // An unfocused window would open the pause menu over every shot.
            client.options.pauseOnLostFocus = false;
            if (client.screen != null) client.setScreen(null);
            if (tick == SETTLE_TICKS) {
                client.options.hideGui = true;
                send(client, "gamemode spectator");
                for (String command : commands) send(client, command);
                return;
            }
            int index = (tick - SETTLE_TICKS - 20) / VIEW_TICKS;
            int phase = (tick - SETTLE_TICKS - 20) % VIEW_TICKS;
            if (tick < SETTLE_TICKS + 20) return;
            if (index >= views.size()) {
                CsmcMod.LOGGER.info("[csmc surf dev] Done");
                ticks[0] = -1;
                return;
            }
            View view = views.get(index);
            if (phase == 0) send(client, view.tp());
            else if (phase == VIEW_TICKS - 1) {
                Screenshot.grab(client.gameDirectory, view.name() + ".png", client.getMainRenderTarget(),
                        message -> CsmcMod.LOGGER.info("[csmc surf dev] {}", message.getString()));
            }
        });
    }

    private static void send(Minecraft client, String command) {
        CsmcMod.LOGGER.info("[csmc surf dev] /{}", command);
        client.player.connection.sendCommand(command);
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
