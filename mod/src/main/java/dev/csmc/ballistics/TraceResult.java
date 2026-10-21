package dev.csmc.ballistics;

import dev.csmc.data.WeaponData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic outcome of one bullet trace. Impacts are listed in the order the bullet met them
 * (ascending distance from {@link #start()}). Distances are in blocks.
 *
 * @param remainingDamage CS damage the bullet still carried where it ended
 * @param remainingPenetrations obstacles the bullet could still have passed completely
 */
public record TraceResult(
        Vec3 start,
        Vec3 direction,
        Vec3 end,
        StopReason stopReason,
        @Nullable BlockPos stoppedAt,
        double remainingDamage,
        int remainingPenetrations,
        List<Impact> impacts) {

    public TraceResult {
        impacts = List.copyOf(impacts);
    }

    public enum StopReason {
        /** Reached the weapon's range without being stopped. */
        RANGE,
        /** Material is marked {@code stops} or is unbreakable (hardness &lt; 0). */
        BLOCK,
        /** Penetration loss used up the damage, or the weapon/material cannot penetrate at all. */
        POWER,
        /** {@code penetration_count} obstacles already passed, or the obstacle is thicker than {@code penetration_distance}. */
        PENETRATION,
        /**
         * The block may not be entered or changed (gamerule {@code csmcBlockBreaking} off, shooter protection,
         * or another mod cancelled its destruction); the block is unchanged and occludes everything behind it.
         */
        FORBIDDEN,
        /** The ray reached a chunk that is not loaded. */
        UNLOADED
    }

    public enum BlockOutcome {
        /** Bullet passed through without changing the block. */
        PASSED,
        /** Block was destroyed outright (glass, panes, foliage, wool); the bullet still pays for its material. */
        SHATTERED,
        /** Cumulative damage was added but the block survived. */
        DAMAGED,
        /** Cumulative damage reached the break threshold and the block was actually destroyed. */
        BROKEN,
        /** Bullet stopped in this block without modifying it (beyond cumulative damage, if any). */
        STOPPED
    }

    public sealed interface Impact permits BlockImpact, EntityImpact {
        double distance();

        Vec3 location();
    }

    /**
     * @param material name of the {@code MaterialTable} entry that matched
     * @param csDamage bullet damage (CS hit points, after falloff) when it reached the block
     * @param thicknessUnits CS units the ray travels inside this block's collision shape
     */
    public record BlockImpact(
            BlockPos pos, BlockState state, String material, BlockOutcome outcome, double csDamage,
            double thicknessUnits, double distance, Vec3 location)
            implements Impact {}

    /**
     * @param csDamage bullet damage in CS hit points after falloff, hit group and armor
     * @param healthDamage Minecraft health requested from {@link Entity#hurt}
     * @param hurt whether vanilla accepted the damage (false for PvP off, teams, invulnerability...)
     */
    public record EntityImpact(
            Entity entity, HitGroup hitGroup, double csDamage, float healthDamage, boolean hurt, double distance, Vec3 location)
            implements Impact {}

    /** CS hit groups with distinct damage multipliers (arms are folded into chest, both x1). */
    public enum HitGroup {
        GENERIC(1.0),
        /** Uses the weapon's {@code headshot_multiplier}. */
        HEAD(Double.NaN),
        CHEST(1.0),
        STOMACH(1.25),
        LEGS(0.75);

        private final double multiplier;

        HitGroup(double multiplier) {
            this.multiplier = multiplier;
        }

        public double multiplier(WeaponData weapon) {
            return this == HEAD ? weapon.headshotMultiplier() : multiplier;
        }
    }
}
