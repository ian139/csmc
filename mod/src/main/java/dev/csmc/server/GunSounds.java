package dev.csmc.server;

import dev.csmc.network.GunInspectPayload;
import dev.csmc.network.GunSoundPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Sends server-confirmed weapon events (sounds, inspect state) to the shooter and every player tracking them. */
public final class GunSounds {
    private GunSounds() {}

    public static void broadcast(ServerPlayer shooter, ResourceLocation weapon, byte event, Vec3 position, int durationTicks) {
        broadcast(shooter, weapon, event, position, durationTicks, 0, -1, 0);
    }

    /** Full event; see {@link GunSoundPayload} for {@code sequence}, {@code magazine} and {@code rounds}. */
    public static void broadcast(ServerPlayer shooter, ResourceLocation weapon, byte event, Vec3 position,
                                 int durationTicks, int sequence, int magazine, int rounds) {
        GunSoundPayload payload = new GunSoundPayload(weapon.getPath(), event, position.x, position.y, position.z,
                shooter.getId(), durationTicks, sequence, magazine, rounds);
        send(shooter, payload);
        for (ServerPlayer watcher : PlayerLookup.tracking(shooter)) {
            if (watcher != shooter) send(watcher, payload);
        }
    }

    public static void broadcastInspect(ServerPlayer shooter, ResourceLocation weapon, int slot, boolean active) {
        GunInspectPayload payload = new GunInspectPayload(shooter.getId(), weapon, slot, active);
        sendInspect(shooter, payload);
        for (ServerPlayer watcher : PlayerLookup.tracking(shooter)) {
            if (watcher != shooter) sendInspect(watcher, payload);
        }
    }

    private static void send(ServerPlayer player, GunSoundPayload payload) {
        if (ServerPlayNetworking.canSend(player, GunSoundPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }

    private static void sendInspect(ServerPlayer player, GunInspectPayload payload) {
        if (ServerPlayNetworking.canSend(player, GunInspectPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
}
