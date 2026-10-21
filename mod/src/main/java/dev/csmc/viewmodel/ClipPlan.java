package dev.csmc.viewmodel;

/**
 * The clip segments one viewmodel playback runs through, back to back: a single clip for most actions, fire then a
 * bolt/pump cycle for snipers and shotguns, or start, one loop per shell and end for a shotgun reload. Each segment
 * plays its clip from 0 to its full length over {@code length} playback seconds, so a segment may be time-scaled.
 * Fixed capacity; reused for every playback, so planning and sampling allocate nothing.
 */
public final class ClipPlan {
    /** Most segments one plan holds (a shotgun reload of 60 shells is far beyond any CS2 weapon). */
    public static final int CAPACITY = 64;

    private final String[] clips = new String[CAPACITY];
    private final float[] lengths = new float[CAPACITY];
    private final float[] clipLengths = new float[CAPACITY];
    private int size;
    private float total;

    public void clear() {
        size = 0;
        total = 0;
    }

    /** Appends {@code clip} at its natural speed; ignored when null, zero-length or full. */
    public ClipPlan add(String clip, float clipLength) {
        if (clip == null || clipLength <= 0 || size == CAPACITY) return this;
        clips[size] = clip;
        lengths[size] = clipLength;
        clipLengths[size] = clipLength;
        size++;
        total += clipLength;
        return this;
    }

    /** Uniformly time-scales every segment so the whole plan lasts {@code seconds}; no-op when either is zero. */
    public ClipPlan scaleTo(float seconds) {
        if (seconds <= 0 || total <= 0) return this;
        float factor = seconds / total;
        for (int i = 0; i < size; i++) lengths[i] *= factor;
        total = seconds;
        return this;
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    /** Playback seconds the whole plan lasts. */
    public float total() { return total; }

    public String clip(int segment) { return clips[segment]; }

    /** Playback seconds segment {@code segment} lasts. */
    public float length(int segment) { return lengths[segment]; }

    /**
     * The segment playing {@code seconds} into the plan (clamped to the first/last), or -1 when empty.
     * {@link #clipTime} gives the time within that segment's clip.
     */
    public int segment(float seconds) {
        if (size == 0) return -1;
        float start = 0;
        for (int i = 0; i < size - 1; i++) {
            if (seconds < start + lengths[i]) return i;
            start += lengths[i];
        }
        return size - 1;
    }

    /** Seconds into segment {@code segment}'s clip at plan time {@code seconds}, within [0, clip length]. */
    public float clipTime(int segment, float seconds) {
        float start = 0;
        for (int i = 0; i < segment; i++) start += lengths[i];
        float local = (seconds - start) * clipLengths[segment] / lengths[segment];
        return Math.max(0, Math.min(clipLengths[segment], local));
    }
}
