package dev.csmc.grenade;

import dev.csmc.data.WeaponData;
import dev.csmc.item.GrenadeItem;
import dev.csmc.network.GrenadeThrowPayload;
import dev.csmc.thirdperson.GrenadePrime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * CS:GO {@code CBaseCSGrenade::ThrowGrenade}, ported to blocks and ticks. The client only reports the button
 * combination pressed while primed ({@link Strength}); the server computes the launch from its own player state.
 *
 * <ul>
 *   <li>Pitch: {@code pitch -= 10 * (90 - |pitch|) / 90}, a slight upward tilt that fades out at straight up/down.
 *   <li>Speed: {@code clamp(throwVelocity * 0.9, 15, 750) * (strength * 0.7 + 0.3)} units/s.
 *   <li>Source: eye + up {@code (strength * 12 - 12)} units, then a hull trace {@value #FORWARD_UNITS} units
 *       along the throw. Both offsets are swept from the eye, so floors, walls and the world border stop them.
 *   <li>Velocity: {@code forward * speed + playerVelocity * 1.25}.
 * </ul>
 */
public final class GrenadeThrow {
    public static final double FORWARD_UNITS = 22.0;
    public static final double PITCH_TILT_DEGREES = 10.0;
    public static final double MOMENTUM_SCALE = 1.25;

    /** Strength by button combination: attack = overhand, use = underhand lob, both = medium. */
    public enum Strength {
        OVERHAND(1.0), LOB(0.0), MEDIUM(0.5);

        public final double value;

        Strength(double value) {
            this.value = value;
        }

        public static Strength of(boolean attack, boolean use) {
            return attack && use ? MEDIUM : use ? LOB : OVERHAND;
        }
    }

    /** {@code position} is the entity position (bottom centre of the projectile box). */
    public record Launch(Vec3 position, Vec3 velocity) {}

    /** Last accepted sequence per player; a request must be strictly newer. */
    private static final Map<UUID, Integer> LAST_SEQUENCE = new ConcurrentHashMap<>();

    private GrenadeThrow() {}

    public static float tiltedPitch(float xRot) {
        float pitch = Mth.wrapDegrees(xRot);
        return (float) (pitch - PITCH_TILT_DEGREES * (90.0 - Math.abs(pitch)) / 90.0);
    }

    /** Launch speed in blocks/tick. */
    public static double speed(double strength) {
        double units = Mth.clamp(GrenadeTuning.THROW_VELOCITY_UNITS * 0.9, 15.0, 750.0) * (strength * 0.7 + 0.3);
        return units / WeaponData.UNITS_PER_BLOCK / 20.0;
    }

    public static Vec3 direction(float xRot, float yRot) {
        return Vec3.directionFromRotation(tiltedPitch(xRot), yRot);
    }

    public static Vec3 velocity(float xRot, float yRot, Vec3 playerVelocity, double strength) {
        return direction(xRot, yRot).scale(speed(strength)).add(playerVelocity.scale(MOMENTUM_SCALE));
    }

    /** Throw source before the wall trace (centre of the projectile). */
    public static Vec3 source(Vec3 eye, double strength) {
        return eye.add(0.0, (strength * 12.0 - 12.0) / WeaponData.UNITS_PER_BLOCK, 0.0);
    }

    /** Full launch from an eye position, view angles and the thrower's velocity (blocks/tick). */
    public static Launch launch(GrenadeArc.Collider collider, Vec3 eye, float xRot, float yRot, Vec3 playerVelocity,
            double strength) {
        double half = GrenadeTuning.PROJECTILE_SIZE / 2.0;
        // Sweep from a box centred on the eye (inside the player's own free hull): first the strength's vertical
        // offset, so a crawling lob stops on the floor, then forward, so a wall or world border stops it.
        Vec3 eyeBox = eye.subtract(0.0, half, 0.0);
        Vec3 lowered = eyeBox.add(collider.collide(eyeBox, source(eye, strength).subtract(eye)));
        Vec3 reach = direction(xRot, yRot).scale(FORWARD_UNITS / WeaponData.UNITS_PER_BLOCK);
        Vec3 position = lowered.add(collider.collide(lowered, reach));
        return new Launch(position, velocity(xRot, yRot, playerVelocity, strength));
    }

    /** Player velocity used for momentum: grounded players carry no vertical (gravity) component. */
    public static Vec3 playerVelocity(ServerPlayer player) {
        Vec3 known = player.getKnownMovement();
        return new Vec3(known.x, player.onGround() ? 0.0 : known.y, known.z);
    }

    public static Launch launch(ServerPlayer player, Strength strength) {
        return launch(GrenadeArc.collider(player.level()), player.getEyePosition(), player.getXRot(),
                player.getYRot(), playerVelocity(player), strength.value);
    }

    /** Validates and performs a client throw request. Returns the thrown grenade, or null when rejected. */
    public static GrenadeProjectile handle(ServerPlayer player, GrenadeThrowPayload request) {
        if (request.strength() == null || !player.isAlive() || player.isSpectator()
                || !(player.level() instanceof ServerLevel level)) {
            return null;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof GrenadeItem item) || player.getCooldowns().isOnCooldown(item)) {
            return null;
        }
        Integer last = LAST_SEQUENCE.get(player.getUUID());
        if (last != null && request.sequence() - last <= 0) {
            return null;
        }
        LAST_SEQUENCE.put(player.getUUID(), request.sequence());
        GrenadeProjectile grenade = GrenadeProjectile.launch(level, player, stack.copyWithCount(1),
                launch(player, request.strength()));
        for (GrenadeItem grenadeItem : GrenadeRegistry.ITEMS.values()) {
            player.getCooldowns().addCooldown(grenadeItem, GrenadeTuning.THROW_COOLDOWN_TICKS);
        }
        player.awardStat(Stats.ITEM_USED.get(item));
        stack.consume(1, player);
        GrenadePrime.thrown(player, request.strength());
        return grenade;
    }

    public static void forget(ServerPlayer player) {
        LAST_SEQUENCE.remove(player.getUUID());
    }
}
