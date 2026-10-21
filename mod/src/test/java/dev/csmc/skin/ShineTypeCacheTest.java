package dev.csmc.skin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ShineTypeCacheTest {
    private final AtomicInteger creates = new AtomicInteger();
    private final ShineTypeCache<String, String, String, Object> cache = new ShineTypeCache<>(2, 2,
            (texture, doubleSided, shine, mask, wear) -> {
                creates.incrementAndGet();
                return new Object();
            });

    @Test public void hitsReturnTheCachedValue() {
        Object first = cache.get("t", false, "s", "m", 3);
        assertSame(first, cache.get("t", false, "s", "m", 3));
        assertNotSame(first, cache.get("t", true, "s", "m", 3));
        assertNotSame(first, cache.get("t", false, "s", "m", 4));
        assertEquals(3, creates.get());
    }

    @Test public void variantsPerTextureAreBoundedLeastRecentlyUsed() {
        Object a = cache.get("t", false, "a", "m", 0);
        cache.get("t", false, "b", "m", 0);
        assertSame(a, cache.get("t", false, "a", "m", 0)); // touch a: b is oldest
        cache.get("t", false, "c", "m", 0);
        assertEquals(2, cache.size());
        assertSame(a, cache.get("t", false, "a", "m", 0));
        int before = creates.get();
        cache.get("t", false, "b", "m", 0);
        assertEquals(before + 1, creates.get());
    }

    @Test public void texturesAreBoundedLeastRecentlyUsed() {
        Object one = cache.get("1", false, "s", "m", 0);
        cache.get("2", false, "s", "m", 0);
        cache.get("1", false, "s", "m", 0);
        cache.get("3", false, "s", "m", 0);
        assertEquals(2, cache.size());
        assertSame(one, cache.get("1", false, "s", "m", 0));
        int before = creates.get();
        cache.get("2", false, "s", "m", 0);
        assertEquals(before + 1, creates.get());
    }

    @Test public void sizeNeverExceedsTheProductOfBothBounds() {
        for (int i = 0; i < 1000; i++) cache.get("t" + (i % 7), i % 2 == 0, "s" + i, "m", i);
        assertTrue(cache.size() <= 4);
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test public void hitsDoNotAllocate() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        // Alternating established selections, as a held weapon and its hotbar icon do.
        String full = "full", thumb = "thumb", shine = "s", mask = "m";
        Object sink = null;
        for (int i = 0; i < 20_000; i++) sink = cache.get((i & 1) == 0 ? full : thumb, false, shine, mask, 5);
        long start = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 200_000; i++) sink = cache.get((i & 1) == 0 ? full : thumb, false, shine, mask, 5);
        long allocated = threads.getCurrentThreadAllocatedBytes() - start;
        assertTrue("allocated " + allocated + " bytes", allocated < 4096);
        assertEquals(2, creates.get());
        assertTrue(sink != null);
    }
}
