package dev.csmc.skin.gameplay;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaseScrollTest {
    @Test public void easeIsClampedMonotonicAndDecelerating() {
        assertEquals(0, CaseScroll.ease(-1), 0);
        assertEquals(0, CaseScroll.ease(0), 0);
        assertEquals(1, CaseScroll.ease(1), 0);
        assertEquals(1, CaseScroll.ease(2), 0);
        double previous = 0;
        double previousStep = Double.MAX_VALUE;
        for (int i = 1; i <= 100; i++) {
            double value = CaseScroll.ease(i / 100.0);
            double step = value - previous;
            assertTrue("monotonic at " + i, step >= 0);
            assertTrue("decelerating at " + i, step <= previousStep + 1e-12);
            previous = value;
            previousStep = step;
        }
    }

    @Test public void scrollLandsInsideTheResultTileForEveryJitter() {
        for (double jitter = -1.5; jitter <= 1.5; jitter += 0.05) {
            CaseScroll scroll = CaseScroll.landing(44, jitter, 6);
            assertEquals(CaseScroll.START, scroll.position(0), 1e-12);
            double end = scroll.position(6);
            assertEquals(scroll.target(), end, 1e-12);
            assertEquals("holds after finishing", end, scroll.position(60), 1e-12);
            assertEquals("jitter " + jitter, 44, CaseScroll.tileAt(end));
            assertTrue("stays clear of tile edges", Math.abs(end - 44.5) <= CaseScroll.MAX_JITTER + 1e-12);
        }
    }

    @Test public void markerAdvancesTileByTileToTheResult() {
        CaseScroll scroll = CaseScroll.landing(44, 0.3, 6);
        int crossings = 0;
        int tile = CaseScroll.tileAt(scroll.position(0));
        for (int frame = 1; frame <= 6 * 240; frame++) {
            int now = CaseScroll.tileAt(scroll.position(frame / 240.0));
            assertTrue("never scrolls backwards", now >= tile);
            if (now != tile) crossings++;
            tile = now;
        }
        assertEquals(44, tile);
        // Early fast frames may cross several tiles at once, so at most one tick per tile.
        assertTrue(crossings > 0 && crossings <= 44 - CaseScroll.tileAt(CaseScroll.START));
        assertTrue(scroll.finished(6));
        assertFalse(scroll.finished(5.99));
    }

    @Test public void minimumLandingIndexStaysAheadOfTheStartForEveryJitter() {
        for (double jitter = -1; jitter <= 1; jitter += 0.1) {
            CaseScroll scroll = CaseScroll.landing(CaseScroll.MIN_LANDING_INDEX, jitter, 6);
            assertTrue(scroll.target() > CaseScroll.START);
            assertEquals(CaseScroll.MIN_LANDING_INDEX, CaseScroll.tileAt(scroll.position(6)));
        }
        for (int landing = 0; landing < CaseScroll.MIN_LANDING_INDEX; landing++) {
            int index = landing;
            assertThrows(IllegalArgumentException.class, () -> CaseScroll.landing(index, 1, 6));
        }
    }

    @Test public void prefetchNeverExceedsTheTexturePool() {
        assertEquals(6, CaseScroll.prefetchBudget(9, 32, 6));
        assertEquals(6, CaseScroll.prefetchBudget(26, 32, 6));
        assertEquals(4, CaseScroll.prefetchBudget(28, 32, 6));
        assertEquals(0, CaseScroll.prefetchBudget(32, 32, 6));
        assertEquals(0, CaseScroll.prefetchBudget(40, 32, 6));
        for (int visible = 0; visible <= 40; visible++) {
            int budget = CaseScroll.prefetchBudget(visible, 32, 6);
            assertTrue(budget >= 0 && budget <= 6);
            assertTrue("visible " + visible, visible > 32 || visible + budget <= 32);
        }
    }

    @Test public void prefetchOrderStartsAtTheLandingAndCoversEveryTileOnce() {
        assertArrayEquals(new int[] {3, 2, 4, 1, 5, 0}, CaseScroll.prefetchOrder(6, 3));
        assertArrayEquals(new int[] {4, 3, 2, 1, 0}, CaseScroll.prefetchOrder(5, 4));
        for (int landing = 0; landing < 50; landing++) {
            int[] order = CaseScroll.prefetchOrder(50, landing);
            assertEquals(landing, order[0]);
            boolean[] seen = new boolean[50];
            for (int index : order) {
                assertTrue(!seen[index]);
                seen[index] = true;
            }
        }
    }

    @Test public void distinctVariantsCountsOnlyTheGivenVisibleTiles() {
        // Tile variants by index: 2 and 3 share variant A, 5 repeats 3's variant, 4 is variant B.
        int[] variant = {0, 0, 7, 7, 8, 7};
        CaseScroll.SameVariant same = (a, b) -> variant[a] == variant[b];
        // Tile 2 is culled: its duplicate 3 must still count once.
        assertEquals(2, CaseScroll.distinctVariants(new int[] {3, 4, 5, 0}, 3, same));
        assertEquals(1, CaseScroll.distinctVariants(new int[] {3}, 1, same));
        assertEquals(0, CaseScroll.distinctVariants(new int[] {3, 4}, 0, same));
        assertEquals(2, CaseScroll.distinctVariants(new int[] {2, 3, 4, 5}, 4, same));
    }

    @Test public void rejectsInvalidScrolls() {
        assertThrows(IllegalArgumentException.class, () -> CaseScroll.landing(44, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CaseScroll.landing(1, 0, 6));
    }
}
