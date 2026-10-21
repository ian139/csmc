package dev.csmc.movement;

import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import dev.csmc.movement.physics.CsMovementParams;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

/**
 * Server → owner {@code csmc:movement_state}: the complete CS movement variables in effect for the receiving
 * player ({@code params}, null when the {@code csmcMovement} gamerule is {@code off}) and the CS max ground speed of
 * the item the player holds ({@code heldMaxSpeed}, units/s, unscoped; attribute effects such as the scope slowdown
 * are applied by the client from the synced {@code MOVEMENT_SPEED} attribute). Sending every variable rather than a
 * mode name keeps the client simulation identical to whatever the server runs.
 */
public record MovementStatePayload(@Nullable CsMovementParams params, double heldMaxSpeed)
        implements CustomPacketPayload {
    public static final Type<MovementStatePayload> TYPE = new Type<>(CsmcMod.id("movement_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MovementStatePayload> STREAM_CODEC =
            StreamCodec.of(MovementStatePayload::write, MovementStatePayload::read);
    /** Lowest accepted {@code tickRate}: one substep per Minecraft tick. */
    public static final int MIN_TICK_RATE = 20;
    /** Highest accepted {@code tickRate}: CS:GO 128 tick (6 substeps per Minecraft tick). */
    public static final int MAX_TICK_RATE = 128;
    /** Bound on speed-like variables, units/s (presets: sv_maxvelocity 3500, jump 302, max speed 250). */
    private static final double MAX_SPEED = 10_000.0;
    /** Bound on gravity, units/s² (preset 800). */
    private static final double MAX_ACCELERATION = 10_000.0;
    /** Bound on accelerate/airaccelerate/friction (presets up to 1000). */
    private static final double MAX_ACCELERATE = 100_000.0;
    /** Bound on stamina pool and recovery (presets 80 and 60). */
    private static final double MAX_STAMINA = 1_000.0;

    private static void write(RegistryFriendlyByteBuf buf, MovementStatePayload payload) {
        CsMovementParams p = payload.params;
        buf.writeBoolean(p != null);
        if (p != null) {
            buf.writeDouble(p.gravity());
            buf.writeDouble(p.accelerate());
            buf.writeDouble(p.airAccelerate());
            buf.writeDouble(p.airMaxWishSpeed());
            buf.writeDouble(p.friction());
            buf.writeDouble(p.stopSpeed());
            buf.writeDouble(p.maxVelocity());
            buf.writeDouble(p.jumpImpulse());
            buf.writeBoolean(p.enableBunnyhopping());
            buf.writeBoolean(p.autoBunnyhopping());
            buf.writeDouble(p.staminaMax());
            buf.writeDouble(p.staminaJumpCost());
            buf.writeDouble(p.staminaLandCost());
            buf.writeDouble(p.staminaRecoveryRate());
            buf.writeDouble(p.duckSpeedModifier());
            buf.writeDouble(p.walkSpeedModifier());
            buf.writeVarInt(p.tickRate());
        }
        buf.writeDouble(payload.heldMaxSpeed);
    }

    private static MovementStatePayload read(RegistryFriendlyByteBuf buf) {
        CsMovementParams params = null;
        if (buf.readBoolean()) {
            params = new CsMovementParams(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(),
                    buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readVarInt());
        }
        return new MovementStatePayload(params, buf.readDouble());
    }

    /**
     * Why this payload must not be applied, or null when it is valid. A hostile or corrupt server could otherwise
     * hand the client non-finite variables (which reach collision-query extents) or a tick rate that turns one
     * client tick into millions of substeps. When active: every double finite; {@code tickRate} in
     * [{@value #MIN_TICK_RATE}, {@value #MAX_TICK_RATE}] (1..6 substeps per Minecraft tick, covering CS:GO's 64 and
     * 128 tick); speeds and accelerations within generous multiples of every shipped preset; stamina and speed
     * modifiers within their CS:GO ranges.
     */
    @Nullable
    public String validationError() {
        CsMovementParams p = params;
        // The held speed is unused when off: even a malformed value must not leave the client in its old mode.
        if (p == null) return null;
        if (!Double.isFinite(heldMaxSpeed) || heldMaxSpeed <= 0.0 || heldMaxSpeed > WeaponData.MAX_ALLOWED_SPEED) {
            return "heldMaxSpeed " + heldMaxSpeed;
        }
        if (p.tickRate() < MIN_TICK_RATE || p.tickRate() > MAX_TICK_RATE) return "tickRate " + p.tickRate();
        if (!within(p.gravity(), 0.0, MAX_ACCELERATION)) return "gravity " + p.gravity();
        if (!within(p.accelerate(), 0.0, MAX_ACCELERATE)) return "accelerate " + p.accelerate();
        if (!within(p.airAccelerate(), 0.0, MAX_ACCELERATE)) return "airAccelerate " + p.airAccelerate();
        if (!within(p.airMaxWishSpeed(), 0.0, MAX_SPEED)) return "airMaxWishSpeed " + p.airMaxWishSpeed();
        if (!within(p.friction(), 0.0, MAX_ACCELERATE)) return "friction " + p.friction();
        if (!within(p.stopSpeed(), 0.0, MAX_SPEED)) return "stopSpeed " + p.stopSpeed();
        if (!within(p.maxVelocity(), 1.0, MAX_SPEED)) return "maxVelocity " + p.maxVelocity();
        if (!within(p.jumpImpulse(), 0.0, MAX_SPEED)) return "jumpImpulse " + p.jumpImpulse();
        if (!within(p.staminaMax(), 0.0, MAX_STAMINA)) return "staminaMax " + p.staminaMax();
        if (!within(p.staminaJumpCost(), 0.0, 1.0)) return "staminaJumpCost " + p.staminaJumpCost();
        if (!within(p.staminaLandCost(), 0.0, 1.0)) return "staminaLandCost " + p.staminaLandCost();
        if (!within(p.staminaRecoveryRate(), 0.0, MAX_STAMINA)) return "staminaRecoveryRate " + p.staminaRecoveryRate();
        if (!within(p.duckSpeedModifier(), 0.0, 1.0)) return "duckSpeedModifier " + p.duckSpeedModifier();
        if (!within(p.walkSpeedModifier(), 0.0, 1.0)) return "walkSpeedModifier " + p.walkSpeedModifier();
        return null;
    }

    private static boolean within(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
