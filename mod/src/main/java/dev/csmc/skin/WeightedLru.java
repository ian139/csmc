package dev.csmc.skin;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.ToLongFunction;

/**
 * Thread-safe least-recently-used cache bounded by total weight. Concurrent requests for one key share a single
 * load; loads run outside the lock. Values heavier than the whole budget are returned but never retained.
 */
public final class WeightedLru<K, V> {
    @FunctionalInterface public interface Loader<V> { V load() throws IOException; }

    private final long capacity;
    private final ToLongFunction<V> weigher;
    private final LinkedHashMap<K, CompletableFuture<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<K, Long> weights = new java.util.HashMap<>();
    private long weight;

    public WeightedLru(long capacity, ToLongFunction<V> weigher) {
        this.capacity = capacity;
        this.weigher = weigher;
    }

    public V get(K key, Loader<V> loader) throws IOException {
        CompletableFuture<V> future;
        boolean owner = false;
        synchronized (this) {
            future = entries.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                entries.put(key, future);
                owner = true;
            }
        }
        if (owner) {
            V value;
            try {
                value = loader.load();
            } catch (IOException | RuntimeException | Error e) {
                synchronized (this) { entries.remove(key, future); }
                future.completeExceptionally(e);
                throw e;
            }
            future.complete(value);
            synchronized (this) {
                long w = weigher.applyAsLong(value);
                if (w > capacity) {
                    entries.remove(key, future);
                } else if (entries.get(key) == future) {
                    weights.put(key, w);
                    weight += w;
                    evict(key);
                }
            }
            return value;
        }
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    private void evict(K keep) {
        Iterator<Map.Entry<K, CompletableFuture<V>>> it = entries.entrySet().iterator();
        while (weight > capacity && it.hasNext()) {
            Map.Entry<K, CompletableFuture<V>> entry = it.next();
            Long w = weights.get(entry.getKey());
            if (w == null || entry.getKey().equals(keep)) continue; // still loading, or the value just stored
            it.remove();
            weights.remove(entry.getKey());
            weight -= w;
        }
    }

    public synchronized long weight() { return weight; }
    public synchronized boolean contains(K key) { return entries.containsKey(key); }
    public synchronized void clear() {
        entries.clear();
        weights.clear();
        weight = 0;
    }
}
