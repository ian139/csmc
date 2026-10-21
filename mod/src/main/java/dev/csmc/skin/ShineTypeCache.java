package dev.csmc.skin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, allocation-free-on-hit cache of shine render layers: at most {@code textures} base textures (least recently
 * used evicted), each with at most {@code variants} cull/shine/mask/wear combinations (least recently used replaced).
 * Hits compare the caller's existing objects in place, so steady-state frames build no lookup key. Single-threaded
 * (render thread); evicted values stay valid for whoever still holds them.
 */
public final class ShineTypeCache<T, S, M, V> {
    @FunctionalInterface
    public interface Factory<T, S, M, V> {
        V create(T texture, boolean doubleSided, S shine, M mask, int wearStep);
    }

    private final int variants;
    private final Factory<T, S, M, V> factory;
    private final LinkedHashMap<T, Bucket> buckets;
    private int size;

    public ShineTypeCache(int textures, int variants, Factory<T, S, M, V> factory) {
        if (textures < 1 || variants < 1) throw new IllegalArgumentException("bounds must be positive");
        this.variants = variants;
        this.factory = factory;
        buckets = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<T, Bucket> eldest) {
                if (size() <= textures) return false;
                ShineTypeCache.this.size -= eldest.getValue().count;
                return true;
            }
        };
    }

    public V get(T texture, boolean doubleSided, S shine, M mask, int wearStep) {
        Bucket bucket = buckets.get(texture);
        if (bucket == null) {
            bucket = new Bucket(variants);
            buckets.put(texture, bucket);
        }
        return bucket.get(texture, doubleSided, shine, mask, wearStep);
    }

    /** Cached values across all textures; at most {@code textures * variants}. */
    public int size() {
        return size;
    }

    public void clear() {
        buckets.clear();
        size = 0;
    }

    private final class Bucket {
        final boolean[] sides;
        final Object[] shines;
        final Object[] masks;
        final int[] wears;
        final Object[] values;
        int count;

        Bucket(int capacity) {
            sides = new boolean[capacity];
            shines = new Object[capacity];
            masks = new Object[capacity];
            wears = new int[capacity];
            values = new Object[capacity];
        }

        @SuppressWarnings("unchecked")
        V get(T texture, boolean doubleSided, S shine, M mask, int wearStep) {
            for (int i = 0; i < count; i++) {
                if (sides[i] == doubleSided && wears[i] == wearStep && same(shines[i], shine) && same(masks[i], mask)) {
                    Object value = values[i];
                    moveToFront(i, doubleSided, shine, mask, wearStep, value);
                    return (V) value;
                }
            }
            V value = factory.create(texture, doubleSided, shine, mask, wearStep);
            int last;
            if (count < values.length) {
                last = count++;
                size++;
            } else {
                last = count - 1; // replace the least recently used variant
            }
            moveToFront(last, doubleSided, shine, mask, wearStep, value);
            return value;
        }

        private void moveToFront(int index, boolean doubleSided, Object shine, Object mask, int wearStep, Object value) {
            System.arraycopy(sides, 0, sides, 1, index);
            System.arraycopy(shines, 0, shines, 1, index);
            System.arraycopy(masks, 0, masks, 1, index);
            System.arraycopy(wears, 0, wears, 1, index);
            System.arraycopy(values, 0, values, 1, index);
            sides[0] = doubleSided;
            shines[0] = shine;
            masks[0] = mask;
            wears[0] = wearStep;
            values[0] = value;
        }

        private static boolean same(Object a, Object b) {
            return a == b || a != null && a.equals(b);
        }
    }
}
