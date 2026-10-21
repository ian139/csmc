package dev.csmc.ballistics;

import dev.csmc.data.MaterialTable;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The one way CS damage (bullets, grenade blasts) changes blocks. The block's {@link MaterialTable} entry
 * decides how: {@code SHATTER} breaks on any positive hit, {@code CUMULATIVE} adds the damage to the shared
 * per-position total of {@link BlockDamageTracker} (crack overlay) and breaks when it reaches
 * {@code break_damage}. {@code NONE}, {@code stops}, hardness &lt; 0 and blocks with a block entity
 * (containers, beds, lecterns... whose removal would spill or lose contents) are never changed. Every change
 * requires {@link BlockBreakPolicy#mayModify}; breaking a door/tall-plant half or piston head/base also needs
 * approval of its counterpart and removes both (see {@link BlockBreakPolicy#destroy}). Broken blocks never drop
 * items. Server thread only.
 */
public final class BlockDamage {
    public enum Outcome {
        /** Not permitted (gamerule, protection, unloaded chunk) or the destruction failed: nothing changed. */
        DENIED,
        /** The block is still there; {@code appliedDamage} &gt; 0 if it was added to its cumulative total. */
        INTACT,
        /** The block was actually destroyed. */
        BROKEN
    }

    /** @param appliedDamage CS damage credited to the block (0 unless it was damaged or broken) */
    public record Result(Outcome outcome, double appliedDamage) {}

    private static final Result DENIED = new Result(Outcome.DENIED, 0.0);
    private static final Result UNCHANGED = new Result(Outcome.INTACT, 0.0);

    private BlockDamage() {}

    /**
     * Applies {@code csDamage} CS damage to the block at {@code pos} on behalf of {@code player}. Never loads
     * chunks: an unloaded position is {@link Outcome#DENIED}. {@code player} null is for diagnostics only
     * (then only hardness, gamerule and world border are checked); gameplay callers must pass the owner and
     * skip the call when the owner is unknown.
     */
    public static Result apply(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos, double csDamage) {
        if (!level.isLoaded(pos)) {
            return DENIED;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return UNCHANGED;
        }
        MaterialTable.Material material = MaterialTable.lookup(state);
        if (!(csDamage > 0.0) || !Double.isFinite(csDamage) || material.stops()
                || material.breakMode() == MaterialTable.BreakMode.NONE || state.hasBlockEntity()
                || state.getDestroySpeed(level, pos) < 0.0F) {
            return UNCHANGED;
        }
        if (!BlockBreakPolicy.mayModify(level, player, pos, state)) {
            return DENIED;
        }
        return applyPermitted(level, player, pos, state, material, csDamage, (removedPos, removedState) -> {});
    }

    /**
     * The damage step for a breakable ({@code SHATTER}/{@code CUMULATIVE}, not {@code stops}) material whose
     * hit {@link BlockBreakPolicy#mayModify} has already approved, so protection events fire once per hit.
     * {@code removedCounterpart} receives the position and original state of an assembly counterpart that the
     * break also removed.
     */
    static Result applyPermitted(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos, BlockState state,
            MaterialTable.Material material, double csDamage, BiConsumer<BlockPos, BlockState> removedCounterpart) {
        if (!(csDamage > 0.0) || !Double.isFinite(csDamage) || state.hasBlockEntity()) {
            return UNCHANGED;
        }
        boolean breaks = material.breakMode() == MaterialTable.BreakMode.SHATTER
                || BlockDamageTracker.damage(level, pos, state) + csDamage >= material.breakDamage();
        if (!breaks) {
            BlockDamageTracker.add(level, pos, state, csDamage, material.breakDamage());
            return new Result(Outcome.INTACT, csDamage);
        }
        if (!BlockBreakPolicy.destroy(level, player, pos, state, removedCounterpart)) {
            // Earlier damage and its cracks stay as they were; this hit changed nothing.
            return DENIED;
        }
        BlockDamageTracker.clear(level, pos);
        return new Result(Outcome.BROKEN, csDamage);
    }
}
