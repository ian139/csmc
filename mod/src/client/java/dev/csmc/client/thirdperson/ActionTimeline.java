package dev.csmc.client.thirdperson;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Latest remote action per entity id, stamped with client receipt time. Written from network handlers on the render
 * thread; read by providers. One action at a time per entity: a new action replaces the old (cancellation = a
 * {@link Action#NONE} write). Cleared on disconnect and entity unload.
 */
public final class ActionTimeline {
    public enum Action {
        NONE, DRAW, FIRE, RELOAD, INSPECT, PRIME,
        KNIFE_SLASH, KNIFE_STAB, KNIFE_BACKSTAB, KNIFE_DRAW, KNIFE_INSPECT,
        GRENADE_PRIME, GRENADE_THROW, EQUIPMENT_USE
    }

    public record Entry(Action action, double startSeconds, double durationSeconds, int variant) {}

    private static final Int2ObjectOpenHashMap<Entry> ENTRIES = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<int[]> SCOPE = new Int2ObjectOpenHashMap<>();

    private ActionTimeline() {}

    public static void start(int entityId, Action action, double nowSeconds, double durationSeconds, int variant) {
        if (action == Action.NONE) ENTRIES.remove(entityId);
        else ENTRIES.put(entityId, new Entry(action, nowSeconds, durationSeconds, variant));
    }

    public static Entry current(int entityId) {
        return ENTRIES.get(entityId);
    }

    /** Remote scope level (0 = unscoped), from the server's per-tracker scope broadcast. */
    public static void scope(int entityId, int level) {
        if (level <= 0) SCOPE.remove(entityId);
        else SCOPE.put(entityId, new int[] {level});
    }

    public static int scopeLevel(int entityId) {
        int[] level = SCOPE.get(entityId);
        return level == null ? 0 : level[0];
    }

    public static void forget(int entityId) {
        ENTRIES.remove(entityId);
        SCOPE.remove(entityId);
    }

    public static void clear() {
        ENTRIES.clear();
        SCOPE.clear();
    }

    public static double now() {
        return System.nanoTime() * 1.0E-9;
    }
}
