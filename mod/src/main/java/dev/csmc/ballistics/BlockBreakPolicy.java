package dev.csmc.ballistics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether CS damage (bullets, grenades) may touch a block, and performs no-drop breaks. Every
 * bullet-entered or blast-damaged block goes through here so protection is enforced in one place.
 */
public final class BlockBreakPolicy {
    /**
     * {@code /gamerule csmcBlockBreaking false} disables all bullet and grenade block damage. Bullets then
     * stop ({@code FORBIDDEN}) at the first solid block they enter: no block changes and no wallbangs.
     */
    public static final GameRules.Key<GameRules.BooleanValue> BLOCK_BREAKING_RULE = GameRuleRegistry.register(
            "csmcBlockBreaking", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(true));

    private BlockBreakPolicy() {}

    /** Forces class initialization so the gamerule is registered during mod init. */
    static void register() {}

    /**
     * Whether CS damage from {@code shooter} may modify the block, which bullets also require to pass
     * through it. Checks, in order: hardness &ge; 0, the {@code csmcBlockBreaking} gamerule, and for a
     * shooter the vanilla game-mode restriction (adventure/spectator), {@link ServerPlayer#mayInteract}
     * (spawn protection and world border), and Fabric {@link PlayerBlockBreakEvents#BEFORE} so
     * claim/protection mods can veto. A veto fires {@link PlayerBlockBreakEvents#CANCELED} like a vanilla
     * break would. If a callback changes the block while approving, the answer is no: the approval does not
     * carry over to the replacement. Without a shooter (diagnostic traces) only hardness, gamerule and world
     * border apply.
     */
    public static boolean mayModify(ServerLevel level, @Nullable ServerPlayer shooter, BlockPos pos, BlockState state) {
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        if (!level.getGameRules().getBoolean(BLOCK_BREAKING_RULE)) {
            return false;
        }
        if (shooter == null) {
            return level.getWorldBorder().isWithinBounds(pos);
        }
        if (shooter.blockActionRestricted(level, pos, shooter.gameMode.getGameModeForPlayer())) {
            return false;
        }
        if (!shooter.mayInteract(level, pos)) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, shooter, pos, state, blockEntity)) {
            PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, shooter, pos, state, blockEntity);
            return false;
        }
        // A callback may have changed the block while approving it; the approval only covers the old state.
        return level.getBlockState(pos) == state;
    }

    /**
     * Destroys a block already approved by {@link #mayModify} (the hit position is not re-checked) if it is
     * still exactly {@code state}, together with the rest of its assembly: the other half of a door, tall
     * plant or other {@code DOUBLE_BLOCK_HALF} block, and the base or head of an extended piston. Every
     * counterpart must itself pass {@link #mayModify} and have no block entity, and is resolved before
     * anything changes; a counterpart in an unloaded chunk, or one changed by an approval callback, leaves
     * the whole assembly untouched. Nothing drops (CS damage must not become a mining tool): not the
     * assembly, and not blocks that vanilla breaks synchronously because they lost their support (carpet,
     * redstone dust...; see {@link #dropsSuppressed}). Vanilla level event
     * 2001 supplies break particles and sound; {@link PlayerBlockBreakEvents#AFTER} fires per removed
     * position when a shooter is known. Returns whether the approved block was actually removed.
     */
    public static boolean destroy(ServerLevel level, @Nullable ServerPlayer shooter, BlockPos pos, BlockState state) {
        return destroy(level, shooter, pos, state, (removedPos, removedState) -> {});
    }

    /**
     * {@link #destroy(ServerLevel, ServerPlayer, BlockPos, BlockState)} that also reports the assembly counterpart
     * it removed (position and original state), so a bullet can still pay for crossing it.
     */
    static boolean destroy(ServerLevel level, @Nullable ServerPlayer shooter, BlockPos pos, BlockState state,
            BiConsumer<BlockPos, BlockState> removedCounterpart) {
        if (level.getBlockState(pos) != state) {
            // Replaced since approval (or already gone): never remove a block nobody approved.
            return false;
        }
        Counterpart part = counterpart(level, pos, state);
        if (part == Counterpart.UNRESOLVED) {
            return false;
        }
        if (part != null) {
            if (part.state().hasBlockEntity() || !mayModify(level, shooter, part.pos(), part.state())
                    || level.getBlockState(pos) != state || level.getBlockState(part.pos()) != part.state()) {
                return false;
            }
        }
        Scope scope = SCOPE.get();
        if (scope == null) {
            scope = new Scope();
            SCOPE.set(scope);
        }
        List<Removed> removed = new ArrayList<>(2);
        boolean destroyed = false;
        Throwable failure = null;
        scope.depth++;
        try {
            destroyed = removeAssembly(level, shooter, pos, state, part, removedCounterpart, removed);
        } catch (Throwable t) {
            failure = t;
        }
        // Removals that happened are delivered even when this break (or one it contains) failed afterwards.
        scope.pending.addAll(removed);
        if (--scope.depth == 0) {
            // Outermost CS break done: release this thread's scope, then deliver every queued callback (including
            // those of CS breaks nested in its cascade) outside suppression, so a listener's own unrelated breaks
            // drop normally and a CS break it causes starts a fresh scope.
            List<Removed> callbacks = List.copyOf(scope.pending);
            SCOPE.remove();
            failure = deliverAfter(callbacks, failure);
        }
        if (failure != null) {
            rethrow(failure);
        }
        return destroyed;
    }

    /**
     * Fires {@link PlayerBlockBreakEvents#AFTER} for every removal even if a listener throws; returns
     * {@code failure}, or the first listener exception, with later ones added as suppressed.
     */
    @Nullable
    private static Throwable deliverAfter(List<Removed> callbacks, @Nullable Throwable failure) {
        for (Removed r : callbacks) {
            try {
                PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(r.level(), r.shooter(), r.pos(), r.state(), r.blockEntity());
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                } else if (t != failure) {
                    failure.addSuppressed(t);
                }
            }
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException(failure);
    }

    /** A position a CS break removed, for its {@link PlayerBlockBreakEvents#AFTER} callbacks. */
    private record Removed(ServerLevel level, ServerPlayer shooter, BlockPos pos, BlockState state,
            @Nullable BlockEntity blockEntity) {}

    /** Per-thread CS break nesting: suppression depth and the AFTER callbacks waiting for the outermost break. */
    private static final class Scope {
        int depth;
        final List<Removed> pending = new ArrayList<>();
    }

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    /**
     * True while this thread is inside a CS-damage {@link #destroy}, including the synchronous neighbour and
     * shape updates it triggers (support cascades such as carpet or redstone dust losing their block): every
     * item drop spawned there is cancelled. Always false on other threads, outside that call and during
     * {@link PlayerBlockBreakEvents#AFTER} callbacks, which are deferred until the outermost CS break (with
     * every CS break nested in its cascade) is done.
     */
    public static boolean dropsSuppressed() {
        Scope scope = SCOPE.get();
        return scope != null && scope.depth > 0;
    }

    /** Whether this thread still holds a CS break scope; false outside {@link #destroy} (GameTests). */
    static boolean scopeBound() {
        return SCOPE.get() != null;
    }

    private static boolean removeAssembly(ServerLevel level, @Nullable ServerPlayer shooter, BlockPos pos,
            BlockState state, @Nullable Counterpart part, BiConsumer<BlockPos, BlockState> removedCounterpart,
            List<Removed> removed) {
        if (part != null && !(state.getBlock() instanceof PistonBaseBlock)) {
            // Remove the counterpart first without shape updates, so the hit block is not broken by its half or
            // base going missing. A piston head's own removal would destroy its base, so the base always goes
            // first: here the head is the hit block.
            if (!removeWithoutShapeUpdates(level, shooter, part, removed)) {
                return false;
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        boolean destroyed = false;
        try {
            destroyed = level.destroyBlock(pos, false, shooter);
        } finally {
            // Recorded once the block is gone, even if its own cascade (neighbour updates, block-entity
            // removal hooks...) throws afterwards.
            if (shooter != null && (destroyed || level.getBlockState(pos) != state)) {
                removed.add(new Removed(level, shooter, pos, state, blockEntity));
            }
        }
        if (part != null) {
            if (destroyed && level.getBlockState(part.pos()) == part.state()) {
                // Hit piston base: its (loot-less) head survived the base's shape update; remove it too.
                removeWithoutShapeUpdates(level, shooter, part, removed);
            }
            // Neighbours of the counterpart see it gone.
            level.getBlockState(part.pos()).updateNeighbourShapes(level, part.pos(), Block.UPDATE_CLIENTS);
            if (destroyed && level.getBlockState(part.pos()) != part.state()) {
                removedCounterpart.accept(part.pos(), part.state());
            }
        }
        return destroyed;
    }

    /** The other block of a two-block assembly; {@link #UNRESOLVED} if it lies in an unloaded chunk. */
    private record Counterpart(BlockPos pos, BlockState state) {
        static final Counterpart UNRESOLVED = new Counterpart(BlockPos.ZERO, Blocks.AIR.defaultBlockState());
    }

    @Nullable
    private static Counterpart counterpart(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos other;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        } else if (state.getBlock() instanceof PistonHeadBlock) {
            other = pos.relative(state.getValue(PistonHeadBlock.FACING).getOpposite());
        } else if (state.getBlock() instanceof PistonBaseBlock && state.getValue(PistonBaseBlock.EXTENDED)) {
            other = pos.relative(state.getValue(PistonBaseBlock.FACING));
        } else {
            return null;
        }
        if (level.isOutsideBuildHeight(other)) {
            return null;
        }
        if (!level.isLoaded(other)) {
            return Counterpart.UNRESOLVED;
        }
        BlockState otherState = level.getBlockState(other);
        return belongsTogether(state, otherState) ? new Counterpart(other, otherState) : null;
    }

    private static boolean belongsTogether(BlockState state, BlockState other) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return other.is(state.getBlock())
                    && other.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
        }
        if (state.getBlock() instanceof PistonHeadBlock) {
            return other.getBlock() instanceof PistonBaseBlock && other.getValue(PistonBaseBlock.EXTENDED)
                    && other.getValue(PistonBaseBlock.FACING) == state.getValue(PistonHeadBlock.FACING);
        }
        return other.is(Blocks.PISTON_HEAD) && other.getValue(PistonHeadBlock.FACING) == state.getValue(PistonBaseBlock.FACING);
    }

    /**
     * Removes an approved counterpart (no block entity) without drops and without updating neighbour shapes,
     * with the vanilla break effect and game event.
     */
    private static boolean removeWithoutShapeUpdates(
            ServerLevel level, @Nullable ServerPlayer shooter, Counterpart part, List<Removed> removed) {
        boolean done = false;
        try {
            done = level.setBlock(part.pos(), level.getFluidState(part.pos()).createLegacyBlock(),
                    Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
        } finally {
            // Recorded once committed, before any notification that might throw.
            if (shooter != null && (done || level.getBlockState(part.pos()) != part.state())) {
                removed.add(new Removed(level, shooter, part.pos(), part.state(), null));
            }
        }
        if (done) {
            level.levelEvent(2001, part.pos(), Block.getId(part.state()));
            level.gameEvent(GameEvent.BLOCK_DESTROY, part.pos(), GameEvent.Context.of(shooter, part.state()));
        }
        return done;
    }
}
