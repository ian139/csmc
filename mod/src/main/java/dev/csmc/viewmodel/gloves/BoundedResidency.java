package dev.csmc.viewmodel.gloves;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Least-recently-used residency for per-glove GPU resources: at most {@code capacity} entries; inserting past it
 * hands the eldest to {@code evict} (which disposes it). {@link #clear} disposes everything (resource reload,
 * disconnect). Single-threaded (render thread).
 */
public final class BoundedResidency<V> {
    private final int capacity;
    private final Consumer<V> evict;
    private final LinkedHashMap<String, V> entries = new LinkedHashMap<>(4, .75f, true);

    public BoundedResidency(int capacity, Consumer<V> evict) {
        if (capacity < 1) throw new IllegalArgumentException("capacity " + capacity);
        this.capacity = capacity;
        this.evict = evict;
    }

    /** The resident entry, marking it most recently used; null when absent. */
    public V get(String key) {
        return entries.get(key);
    }

    /** Makes {@code value} resident under {@code key}, disposing any replaced value and the eldest beyond capacity. */
    public void put(String key, V value) {
        V replaced = entries.put(key, value);
        if (replaced != null && replaced != value) evict.accept(replaced);
        Iterator<Map.Entry<String, V>> eldest = entries.entrySet().iterator();
        while (entries.size() > capacity) {
            V gone = eldest.next().getValue();
            eldest.remove();
            evict.accept(gone);
        }
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.values().forEach(evict);
        entries.clear();
    }
}
