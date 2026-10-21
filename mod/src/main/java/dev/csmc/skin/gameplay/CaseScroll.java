package dev.csmc.skin.gameplay;

/**
 * Reel motion for the case-opening screen, in tile units: tile {@code i} spans {@code [i, i + 1)} and the centre
 * marker sits at {@link #position}. The reel decelerates with a quartic ease-out and stops inside the landing tile.
 */
public record CaseScroll(double start, double target, double durationSeconds) {
    /** Marker starts this many tiles into the reel so the visible strip is filled from the first frame. */
    public static final double START = 4.5;
    /** Largest landing offset from the tile centre, as a fraction of a tile; keeps the stop clear of tile edges. */
    public static final double MAX_JITTER = 0.4;
    /** Smallest landing tile that still lies ahead of {@link #START} for every jitter. */
    public static final int MIN_LANDING_INDEX = (int) Math.ceil(START + MAX_JITTER - 0.5);

    public CaseScroll {
        if (!(durationSeconds > 0) || target < start) throw new IllegalArgumentException("Invalid scroll");
    }

    /** {@code jitter} in [-1, 1] picks where inside the landing tile the marker stops. */
    public static CaseScroll landing(int landingIndex, double jitter, double durationSeconds) {
        if (landingIndex < MIN_LANDING_INDEX) throw new IllegalArgumentException("Landing index before the reel start");
        double offset = Math.max(-1, Math.min(1, jitter)) * MAX_JITTER;
        return new CaseScroll(START, landingIndex + 0.5 + offset, durationSeconds);
    }

    /** Quartic ease-out over {@code t} in [0, 1]: fast start, long CS-like crawl into the result. */
    public static double ease(double t) {
        double clamped = Math.max(0, Math.min(1, t));
        double rest = 1 - clamped;
        return 1 - rest * rest * rest * rest;
    }

    public double position(double elapsedSeconds) {
        return start + (target - start) * ease(elapsedSeconds / durationSeconds);
    }

    public boolean finished(double elapsedSeconds) {
        return elapsedSeconds >= durationSeconds;
    }

    /**
     * Ahead-of-screen tiles whose skin variants may be requested: at most {@code maxPrefetch}, and never more than the
     * texture pool has left after {@code visibleVariants}, so prefetching cannot evict variants still on screen.
     */
    public static int prefetchBudget(int visibleVariants, int poolCapacity, int maxPrefetch) {
        return Math.max(0, Math.min(maxPrefetch, poolCapacity - visibleVariants));
    }

    /**
     * Every tile index once, in load priority: the landing tile, then outward from it, the tile just before the landing
     * before the one just after at each distance. Tiles near the landing scroll slowest and stay up during the reveal.
     */
    public static int[] prefetchOrder(int size, int landing) {
        int[] order = new int[size];
        int count = 0;
        if (landing >= 0 && landing < size) order[count++] = landing;
        for (int distance = 1; count < size; distance++) {
            if (landing - distance >= 0 && landing - distance < size) order[count++] = landing - distance;
            if (landing + distance >= 0 && landing + distance < size) order[count++] = landing + distance;
        }
        return order;
    }

    /** Whether two reel tiles share one skin texture variant. */
    @FunctionalInterface
    public interface SameVariant {
        boolean test(int a, int b);
    }

    /** Distinct variants among the first {@code count} entries of {@code tiles}, which must hold only visible tiles. */
    public static int distinctVariants(int[] tiles, int count, SameVariant same) {
        int distinct = 0;
        for (int i = 0; i < count; i++) {
            boolean repeat = false;
            for (int j = 0; j < i && !repeat; j++) repeat = same.test(tiles[j], tiles[i]);
            if (!repeat) distinct++;
        }
        return distinct;
    }

    /** Tile under the marker at reel position {@code position}. */
    public static int tileAt(double position) {
        return (int) Math.floor(position);
    }
}
