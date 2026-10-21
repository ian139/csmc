package dev.csmc.client.movement;

import dev.csmc.CsmcMod;
import dev.csmc.movement.CsMovement;
import dev.csmc.movement.physics.CsUnits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Dev-only movement harness, inert unless one of its system properties is set. Every line it logs starts with
 * {@code [csmc move]}.
 * <ul>
 *   <li>{@code -Dcsmc.devMovementScript=<file>}: runs a movement script once the client is in a world (syntax below).
 *   Turns off pause-on-lost-focus so held keys survive an unfocused window.</li>
 *   <li>{@code -Dcsmc.devMovementTelemetry=true}: per-tick telemetry from the start (scripts toggle it with
 *   {@code telemetry on|off}): client tick, game time, position, velocity (units/s), horizontal speed, speed measured
 *   from the tick's displacement, CS grounded/surfing/jumped/landed, Minecraft onGround, max speed and mode.</li>
 *   <li>{@code -Dcsmc.devMovementObserve=<name>}: per-tick position of another player's entity as this client sees
 *   it, for comparing an observer's view with that player's own telemetry.</li>
 * </ul>
 * Script lines (blank lines and {@code #} comments ignored; keys: forward back left right jump sneak sprint walk use
 * attack, comma-separated for several):
 * <pre>
 * wait N                 block for N ticks
 * waitground N           block until on ground, at most N ticks
 * hold KEYS N            hold keys for the next N ticks (non-blocking; N = -1 holds until release)
 * press KEYS             hold keys for one tick
 * release KEYS|all       release held keys
 * yaw DEG | pitch DEG    set the view angle
 * yawrate DEG            turn by DEG every tick (0 stops)
 * slot 1-9               select a hotbar slot
 * chat TEXT              chat message, or a command when TEXT starts with /
 * gamerule MODE          /gamerule csmcMovement MODE
 * tp X Y Z [YAW PITCH]   /tp @s X Y Z [YAW PITCH]
 * screenshot NAME        screenshots/NAME.png
 * mark TEXT              log a marker line
 * telemetry on|off       per-tick telemetry
 * quit                   close the client
 * </pre>
 */
final class MovementDevHarness {
    private static final String PREFIX = "[csmc move] ";

    private final List<String[]> steps;
    private final Map<KeyMapping, Integer> held = new HashMap<>();
    private int next;
    private int waitTicks;
    private int groundWait = -1;
    private float yawRate;
    private boolean telemetry;
    private long clientTick;
    private Vec3 lastPos;

    private MovementDevHarness(List<String[]> steps, boolean telemetry) {
        this.steps = steps;
        this.telemetry = telemetry;
    }

    static void register() {
        String script = System.getProperty("csmc.devMovementScript");
        boolean telemetry = Boolean.getBoolean("csmc.devMovementTelemetry");
        String observe = System.getProperty("csmc.devMovementObserve");
        if (script != null && !script.isBlank()) {
            List<String[]> steps = parse(Path.of(script));
            if (steps == null) return;
            MovementDevHarness harness = new MovementDevHarness(steps, telemetry);
            ClientTickEvents.START_CLIENT_TICK.register(harness::startTick);
            ClientTickEvents.END_CLIENT_TICK.register(harness::endTick);
        } else if (telemetry) {
            MovementDevHarness harness = new MovementDevHarness(List.of(), true);
            ClientTickEvents.END_CLIENT_TICK.register(harness::endTick);
        }
        if (observe != null && !observe.isBlank()) {
            CsmcMod.LOGGER.info(PREFIX + "observing {}", observe);
            ClientTickEvents.END_CLIENT_TICK.register(client -> observe(client, observe));
        }
    }

    /** Parsed script, or null after logging every error. */
    private static List<String[]> parse(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            CsmcMod.LOGGER.error(PREFIX + "script {} unreadable: {}", file, e.toString());
            return null;
        }
        List<String[]> steps = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] words = line.split("\\s+");
            String error = validate(words);
            if (error != null) {
                CsmcMod.LOGGER.error(PREFIX + "script {} line {}: {} ({})", file, i + 1, error, line);
                ok = false;
            }
            steps.add(words);
        }
        if (!ok) return null;
        CsmcMod.LOGGER.info(PREFIX + "script {} loaded: {} steps", file, steps.size());
        return steps;
    }

    private static String validate(String[] w) {
        try {
            switch (w[0]) {
                case "wait", "waitground" -> {
                    if (w.length != 2 || Integer.parseInt(w[1]) < 0) return "needs a tick count";
                }
                case "hold" -> {
                    if (w.length != 3) return "needs keys and a tick count";
                    int ticks = Integer.parseInt(w[2]);
                    // 0 would never expire (expiry counts positive holds down); only -1 means indefinitely.
                    if (ticks < -1 || ticks == 0) return "needs a positive tick count or -1";
                    return validateKeys(w[1]);
                }
                case "press" -> {
                    if (w.length != 2) return "needs keys";
                    return validateKeys(w[1]);
                }
                case "release" -> {
                    if (w.length != 2) return "needs keys or all";
                    return w[1].equals("all") ? null : validateKeys(w[1]);
                }
                case "yaw", "pitch", "yawrate" -> {
                    if (w.length != 2) return "needs degrees";
                    Float.parseFloat(w[1]);
                }
                case "slot" -> {
                    if (w.length != 2 || Integer.parseInt(w[1]) < 1 || Integer.parseInt(w[1]) > 9) return "needs 1-9";
                }
                case "tp" -> {
                    if (w.length != 4 && w.length != 6) return "needs X Y Z [YAW PITCH]";
                    for (int i = 1; i < w.length; i++) Double.parseDouble(w[i]);
                }
                case "gamerule", "screenshot" -> {
                    if (w.length != 2) return "needs one argument";
                }
                case "telemetry" -> {
                    if (w.length != 2 || !(w[1].equals("on") || w[1].equals("off"))) return "needs on|off";
                }
                case "chat", "mark" -> {
                    if (w.length < 2) return "needs text";
                }
                case "quit" -> {
                    if (w.length != 1) return "takes no arguments";
                }
                default -> {
                    return "unknown step " + w[0];
                }
            }
        } catch (NumberFormatException e) {
            return "bad number: " + e.getMessage();
        }
        return null;
    }

    private static String validateKeys(String keys) {
        for (String key : keys.split(",")) {
            if (!List.of("forward", "back", "left", "right", "jump", "sneak", "sprint", "walk", "use", "attack")
                    .contains(key)) return "unknown key " + key;
        }
        return null;
    }

    private static KeyMapping key(Minecraft client, String name) {
        return switch (name) {
            case "forward" -> client.options.keyUp;
            case "back" -> client.options.keyDown;
            case "left" -> client.options.keyLeft;
            case "right" -> client.options.keyRight;
            case "jump" -> client.options.keyJump;
            case "sneak" -> client.options.keyShift;
            case "sprint" -> client.options.keySprint;
            case "walk" -> CsMovementClient.walkKey();
            case "use" -> client.options.keyUse;
            case "attack" -> client.options.keyAttack;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private void startTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.getConnection() == null) return;
        client.options.pauseOnLostFocus = false;
        for (Iterator<Map.Entry<KeyMapping, Integer>> it = held.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<KeyMapping, Integer> entry = it.next();
            int left = entry.getValue();
            if (left > 0 && --left == 0) {
                entry.getKey().setDown(false);
                it.remove();
                continue;
            }
            entry.setValue(left);
            // Opening a screen releases every key; a hold outlives that.
            if (!entry.getKey().isDown()) entry.getKey().setDown(true);
        }
        if (yawRate != 0.0F) player.setYRot(player.getYRot() + yawRate);
        if (groundWait >= 0) {
            if (!player.onGround() && groundWait > 0) {
                groundWait--;
                return;
            }
            CsmcMod.LOGGER.info(PREFIX + "waitground done onGround={}", player.onGround());
            groundWait = -1;
        }
        // "wait N" resumes on the Nth tick after it ran, the tick a "hold KEYS N" started with it expires.
        if (waitTicks > 0 && --waitTicks > 0) return;
        while (next < steps.size() && waitTicks == 0 && groundWait < 0) {
            execute(client, player, steps.get(next++));
        }
    }

    private void execute(Minecraft client, LocalPlayer player, String[] w) {
        CsmcMod.LOGGER.info(PREFIX + "step {}", String.join(" ", w));
        switch (w[0]) {
            case "wait" -> waitTicks = Integer.parseInt(w[1]);
            case "waitground" -> groundWait = Integer.parseInt(w[1]);
            case "hold" -> hold(client, w[1], Integer.parseInt(w[2]));
            case "press" -> hold(client, w[1], 1);
            case "release" -> {
                if (w[1].equals("all")) {
                    held.keySet().forEach(mapping -> mapping.setDown(false));
                    held.clear();
                } else {
                    for (String name : w[1].split(",")) {
                        KeyMapping mapping = key(client, name);
                        mapping.setDown(false);
                        held.remove(mapping);
                    }
                }
            }
            case "yaw" -> player.setYRot(Float.parseFloat(w[1]));
            case "pitch" -> player.setXRot(Float.parseFloat(w[1]));
            case "yawrate" -> yawRate = Float.parseFloat(w[1]);
            case "slot" -> player.getInventory().selected = Integer.parseInt(w[1]) - 1;
            case "chat" -> {
                String text = String.join(" ", List.of(w).subList(1, w.length));
                if (text.startsWith("/")) {
                    client.getConnection().sendCommand(text.substring(1));
                } else {
                    client.getConnection().sendChat(text);
                }
            }
            case "gamerule" -> client.getConnection().sendCommand("gamerule csmcMovement " + w[1]);
            case "tp" -> client.getConnection().sendCommand("tp @s " + String.join(" ", List.of(w).subList(1, w.length)));
            case "screenshot" -> Screenshot.grab(client.gameDirectory, w[1] + ".png", client.getMainRenderTarget(),
                    message -> CsmcMod.LOGGER.info(PREFIX + "{}", message.getString()));
            case "mark" -> CsmcMod.LOGGER.info(PREFIX + "mark {}", String.join(" ", List.of(w).subList(1, w.length)));
            case "telemetry" -> telemetry = w[1].equals("on");
            case "quit" -> {
                CsmcMod.LOGGER.info(PREFIX + "script finished, quitting");
                client.stop();
            }
            default -> throw new IllegalStateException("validated step " + w[0]);
        }
    }

    private void hold(Minecraft client, String keys, int ticks) {
        for (String name : keys.split(",")) {
            KeyMapping mapping = key(client, name);
            mapping.setDown(true);
            held.put(mapping, ticks);
        }
    }

    private void endTick(Minecraft client) {
        clientTick++;
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            lastPos = null;
            return;
        }
        Vec3 pos = player.position();
        double measured = lastPos == null ? 0.0 : CsUnits.blocksPerTickToUnitsPerSecond(
                Math.sqrt(sq(pos.x - lastPos.x) + sq(pos.z - lastPos.z)));
        lastPos = pos;
        if (!telemetry) return;
        LocalMovement.TickReport r = LocalMovement.report();
        boolean cs = r.mode == LocalMovement.TickReport.Mode.CS;
        Vec3 dm = player.getDeltaMovement();
        double vx = cs ? r.velocityX : CsUnits.blocksPerTickToUnitsPerSecond(dm.x);
        double vy = cs ? r.velocityY : CsUnits.blocksPerTickToUnitsPerSecond(dm.y);
        double vz = cs ? r.velocityZ : CsUnits.blocksPerTickToUnitsPerSecond(dm.z);
        CsmcMod.LOGGER.info(String.format(Locale.ROOT, PREFIX + "tick=%d gt=%d pos=%.5f,%.5f,%.5f vel=%.2f,%.2f,%.2f "
                        + "hs=%.2f meas=%.2f grounded=%b onGround=%b surf=%b jumped=%b landed=%b max=%.2f mode=%s "
                        + "active=%b clamp=%b duck=%.2f pose=%s yaw=%.2f held=%.1f solids=%d",
                clientTick, client.level.getGameTime(), pos.x, pos.y, pos.z, vx, vy, vz, Math.sqrt(vx * vx + vz * vz),
                measured, cs && r.grounded, player.onGround(), cs && r.surfing, cs && r.jumped, cs && r.landed,
                cs ? r.maxSpeed : 0.0, cs ? "cs" : "vanilla", CsMovement.active(player), cs && r.replayClamped,
                cs ? r.duckMove : 0.0, player.getPose(), player.getYRot(), CsMovement.clientHeldMaxSpeed(),
                cs ? r.analyticSolids : 0));
    }

    private static void observe(Minecraft client, String name) {
        if (client.level == null) return;
        for (Player other : client.level.players()) {
            if (other != client.player && other.getGameProfile().getName().equals(name)) {
                CsmcMod.LOGGER.info(String.format(Locale.ROOT, PREFIX + "observe gt=%d name=%s pos=%.5f,%.5f,%.5f",
                        client.level.getGameTime(), name, other.getX(), other.getY(), other.getZ()));
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
