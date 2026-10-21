package dev.csmc.skin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class WeightedLruTest {
    @Test public void reusesLoadedValues() throws Exception {
        WeightedLru<String, int[]> cache = new WeightedLru<>(100, value -> value.length);
        AtomicInteger loads = new AtomicInteger();
        int[] first = cache.get("a", () -> { loads.incrementAndGet(); return new int[10]; });
        assertSame(first, cache.get("a", () -> { loads.incrementAndGet(); return new int[10]; }));
        assertEquals(1, loads.get());
        assertEquals(10, cache.weight());
    }

    @Test public void evictsLeastRecentlyUsedOverBudget() throws Exception {
        WeightedLru<String, int[]> cache = new WeightedLru<>(30, value -> value.length);
        cache.get("a", () -> new int[10]);
        cache.get("b", () -> new int[10]);
        cache.get("c", () -> new int[10]);
        cache.get("a", () -> new int[10]); // touch a: b is now oldest
        cache.get("d", () -> new int[10]);
        assertTrue(cache.contains("a"));
        assertFalse(cache.contains("b"));
        assertTrue(cache.contains("c") && cache.contains("d"));
        assertEquals(30, cache.weight());
    }

    @Test public void oversizedValuesAreReturnedButNotRetained() throws Exception {
        WeightedLru<String, int[]> cache = new WeightedLru<>(30, value -> value.length);
        cache.get("small", () -> new int[10]);
        assertEquals(40, cache.get("huge", () -> new int[40]).length);
        assertFalse(cache.contains("huge"));
        assertTrue(cache.contains("small"));
        assertEquals(10, cache.weight());
    }

    @Test public void failedLoadsAreNotCached() throws Exception {
        WeightedLru<String, int[]> cache = new WeightedLru<>(30, value -> value.length);
        try {
            cache.get("a", () -> { throw new IOException("boom"); });
            fail();
        } catch (IOException expected) { }
        assertFalse(cache.contains("a"));
        assertEquals(5, cache.get("a", () -> new int[5]).length);
    }

    @Test public void concurrentRequestsShareOneLoad() throws Exception {
        WeightedLru<String, int[]> cache = new WeightedLru<>(100, value -> value.length);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        int[][] seen = new int[2][];
        Thread owner = new Thread(() -> {
            try {
                seen[0] = cache.get("a", () -> {
                    loads.incrementAndGet();
                    started.countDown();
                    try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
                    return new int[3];
                });
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        owner.start();
        started.await();
        Thread waiter = new Thread(() -> {
            try { seen[1] = cache.get("a", () -> { loads.incrementAndGet(); return new int[3]; }); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        waiter.start();
        Thread.sleep(50);
        release.countDown();
        owner.join();
        waiter.join();
        assertEquals(1, loads.get());
        assertSame(seen[0], seen[1]);
    }
}
