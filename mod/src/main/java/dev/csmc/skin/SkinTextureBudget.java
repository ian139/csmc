package dev.csmc.skin;

/**
 * GPU memory the client's skin texture pools may hold at once: full in-hand composites, GUI thumbnails and per-body
 * shine masks. Every slot is RGBA8 without mipmaps, so its cost is {@code edge * edge * 4} bytes.
 */
public final class SkinTextureBudget {
    /** Full-resolution variants held at once. */
    public static final int FULL_VARIANTS = 32;
    /** Thumbnail variants held at once: a whole case strip ({@code CaseOpeningPayload.MAX_STRIP}) plus picker room. */
    public static final int THUMBNAILS = 96;
    /** Shine masks held at once: one per drawn weapon body ({@code skins/<weapon>/_default/shine_mask_<set>.png}). */
    public static final int SHINE_MASKS = 16;
    /** Largest shine mask edge the client uploads; the exporter writes at most this size. */
    public static final int SHINE_MASK_EDGE = 512;
    /** Ceiling for all three pools together. */
    public static final long MAX_BYTES = 176L << 20;

    private SkinTextureBudget() {
    }

    public static long bytes(int slots, int edge) {
        return (long) slots * edge * edge * 4;
    }

    public static long shineMaskBytes() {
        return bytes(SHINE_MASKS, SHINE_MASK_EDGE);
    }

    public static long totalBytes() {
        return bytes(FULL_VARIANTS, SkinResolution.FULL.edge) + bytes(THUMBNAILS, SkinResolution.THUMBNAIL.edge)
                + shineMaskBytes();
    }
}
