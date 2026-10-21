package dev.csmc.ballistics;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Accumulated CS damage on {@code CUMULATIVE} blocks, shared by every shooter, weapon and grenade and keyed by
 * dimension and position, shown to clients with the vanilla block-crack overlay. Server thread only. Damage
 * heals after {@link #FORGET_AFTER_TICKS} without hits (every hit refreshes it), and is dropped when the
 * block state changes.
 */
final class BlockDamageTracker {
    static final int FORGET_AFTER_TICKS = 200;

    private static final Map<ResourceKey<Level>, Long2ObjectMap<Entry>> LEVELS = new HashMap<>();
    /** Negative so the crack "breaker id" never matches a real entity id (vanilla skips that player). */
    private static int nextBreakerId = Integer.MIN_VALUE;

    private static final class Entry {
        final int breakerId;
        double damage;
        long lastHitTick;
        int shownStage = -1;
        BlockState state;

        Entry(int breakerId) {
            this.breakerId = breakerId;
        }
    }

    private BlockDamageTracker() {}

    /** CS damage currently accumulated on the block in this exact state; 0 if none, expired or the state changed. */
    static double damage(ServerLevel level, BlockPos pos, BlockState state) {
        Entry entry = current(level, pos, state);
        return entry == null ? 0.0 : entry.damage;
    }

    /** Whether any entry (and so possibly a crack overlay) exists for the position, regardless of state. */
    static boolean tracked(ServerLevel level, BlockPos pos) {
        Long2ObjectMap<Entry> map = LEVELS.get(level.dimension());
        return map != null && map.containsKey(pos.asLong());
    }

    @Nullable
    private static Entry current(ServerLevel level, BlockPos pos, BlockState state) {
        Long2ObjectMap<Entry> map = LEVELS.get(level.dimension());
        Entry entry = map == null ? null : map.get(pos.asLong());
        return entry != null && entry.state == state && !expired(entry, level.getGameTime()) ? entry : null;
    }

    private static boolean expired(Entry entry, long now) {
        return now - entry.lastHitTick > FORGET_AFTER_TICKS;
    }

    /**
     * Adds CS damage to the block and returns the new total. Updates the crack overlay
     * (stage = 10 * total / breakDamage).
     */
    static double add(ServerLevel level, BlockPos pos, BlockState state,
            double damage, double breakDamage) {
        Long2ObjectMap<Entry> map = LEVELS.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>());
        Entry entry = current(level, pos, state);
        if (entry == null) {
            Entry stale = map.get(pos.asLong());
            if (stale != null) {
                level.destroyBlockProgress(stale.breakerId, pos, -1);
            }
            entry = new Entry(nextBreakerId++);
            entry.state = state;
            map.put(pos.asLong(), entry);
        }
        entry.damage += damage;
        entry.lastHitTick = level.getGameTime();
        int stage = breakDamage > 0.0 ? Math.min(9, (int) (10.0 * entry.damage / breakDamage)) : 9;
        if (stage != entry.shownStage) {
            entry.shownStage = stage;
            level.destroyBlockProgress(entry.breakerId, pos, stage);
        }
        return entry.damage;
    }

    /** Forgets the block (after it broke) and clears its crack overlay. */
    static void clear(ServerLevel level, BlockPos pos) {
        Long2ObjectMap<Entry> map = LEVELS.get(level.dimension());
        if (map == null) {
            return;
        }
        Entry entry = map.remove(pos.asLong());
        if (entry != null) {
            level.destroyBlockProgress(entry.breakerId, pos, -1);
        }
    }

    static void tick(MinecraftServer server) {
        Iterator<Map.Entry<ResourceKey<Level>, Long2ObjectMap<Entry>>> levels = LEVELS.entrySet().iterator();
        while (levels.hasNext()) {
            Map.Entry<ResourceKey<Level>, Long2ObjectMap<Entry>> levelEntry = levels.next();
            ServerLevel level = server.getLevel(levelEntry.getKey());
            if (level == null) {
                levels.remove();
                continue;
            }
            long now = level.getGameTime();
            Iterator<Long2ObjectMap.Entry<Entry>> entries = levelEntry.getValue().long2ObjectEntrySet().iterator();
            while (entries.hasNext()) {
                Long2ObjectMap.Entry<Entry> e = entries.next();
                Entry entry = e.getValue();
                BlockPos pos = BlockPos.of(e.getLongKey());
                boolean changed = level.isLoaded(pos) && level.getBlockState(pos) != entry.state;
                if (changed || expired(entry, now)) {
                    level.destroyBlockProgress(entry.breakerId, pos, -1);
                    entries.remove();
                }
            }
            if (levelEntry.getValue().isEmpty()) {
                levels.remove();
            }
        }
    }

    static void reset() {
        LEVELS.clear();
    }
}
