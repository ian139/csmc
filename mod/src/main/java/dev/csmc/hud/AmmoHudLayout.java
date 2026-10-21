package dev.csmc.hud;

/**
 * Pure placement math for the bottom-right ammo HUD. The readout is laid out in HUD units (icon, thin divider,
 * large magazine count, smaller reserve count; an optional state line sits above) and drawn at a whole number of
 * framebuffer pixels per unit chosen from the screen height, like CS2's panel, so it reads the same at any GUI
 * scale while staying crisp. The GUI scale only sets a floor, and placement avoids the GUI-scaled hotbar.
 */
public final class AmmoHudLayout {
    /** About the magazine digits' height, like CS2; an AK (trimmed aspect 3.4) is then about 8% of screen width. */
    public static final int ICON_HEIGHT = 15;
    /** Very long silhouettes (negev, scar20) are capped so the readout stays compact. */
    public static final int ICON_MAX_WIDTH = 64;
    public static final int MAGAZINE_SCALE = 2;
    public static final int FONT_HEIGHT = 9;
    /** Height of a digit glyph in font pixels. */
    public static final int DIGIT_HEIGHT = 7;
    /** Magazine digit height as a fraction of the screen height (CS2's panel is about 3-4%). */
    public static final double DIGIT_SCREEN_FRACTION = 0.037;
    public static final int PADDING = 3;
    public static final int GAP = 4;
    /** Distance from the right and bottom screen edges, in HUD units. */
    public static final int MARGIN = 8;
    /** Vanilla hotbar half-width and the offhand slot that may sit beside it, in GUI pixels. */
    public static final int HOTBAR_HALF_WIDTH = 91;
    public static final int OFFHAND_WIDTH = 29;
    /** GUI pixels reserved above the screen bottom for the hotbar, XP bar, hearts/armor and food when stacked. */
    public static final int STATUS_CLEARANCE = 62;
    /** Clear space kept between the HUD and the hotbar or status bars, in GUI pixels. */
    public static final int CLEARANCE_GAP = 4;

    private AmmoHudLayout() {}

    /**
     * Origin and scale in GUI pixels; every other field is in HUD units relative to the origin. A HUD unit is
     * {@code scale} GUI pixels, i.e. a whole number of framebuffer pixels.
     */
    public record Placement(double x, double y, double scale, int width, int height, int iconX, int iconWidth,
                            int dividerX, int magazineX, int reserveX, int rowY) {}

    /** Framebuffer pixels per HUD unit: magazine digits at {@link #DIGIT_SCREEN_FRACTION}, never below half the GUI scale. */
    public static int unitPixels(int framebufferHeight, int guiScale) {
        int fromScreen = (int) Math.round(framebufferHeight * DIGIT_SCREEN_FRACTION / (DIGIT_HEIGHT * MAGAZINE_SCALE));
        return Math.max(1, Math.max(fromScreen, (guiScale + 1) / 2));
    }

    /** Icon width in HUD units for a source image, keeping its aspect ratio at {@link #ICON_HEIGHT}. */
    public static int iconWidth(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return ICON_HEIGHT;
        int width = Math.round(ICON_HEIGHT * (float) sourceWidth / sourceHeight);
        return Math.max(1, Math.min(ICON_MAX_WIDTH, width));
    }

    /**
     * Bottom-right box; if it would come within {@link #CLEARANCE_GAP} of the hotbar (or an offhand slot on the
     * right), it moves above the hotbar and status bars instead. Text widths are in font pixels.
     */
    public static Placement place(int guiWidth, int guiHeight, int guiScale, int unitPixels, int iconWidth,
                                  int magazineTextWidth, int reserveTextWidth, boolean offhandOnRight) {
        double scale = (double) unitPixels / guiScale;
        int rowHeight = Math.max(ICON_HEIGHT, FONT_HEIGHT * MAGAZINE_SCALE);
        int iconX = PADDING;
        int dividerX = iconX + iconWidth + GAP;
        int magazineX = dividerX + 1 + GAP;
        int reserveX = magazineX + magazineTextWidth * MAGAZINE_SCALE + GAP / 2;
        int width = reserveX + reserveTextWidth + PADDING;
        int height = PADDING + rowHeight + PADDING;
        double x = guiWidth - (MARGIN + width) * scale;
        double y = guiHeight - (MARGIN + height) * scale;
        int hotbarRight = guiWidth / 2 + HOTBAR_HALF_WIDTH + (offhandOnRight ? OFFHAND_WIDTH : 0);
        if (x < hotbarRight + CLEARANCE_GAP) {
            x = Math.max(0, x);
            y = guiHeight - STATUS_CLEARANCE - CLEARANCE_GAP - height * scale;
        }
        // Snap to framebuffer pixels so glyph edges stay sharp.
        x = Math.floor(x * guiScale) / guiScale;
        y = Math.floor(y * guiScale) / guiScale;
        return new Placement(x, y, scale, width, height, iconX, iconWidth, dividerX, magazineX, reserveX, PADDING);
    }

    /** Last placement, recomputed only when one of its inputs changes so steady frames allocate nothing. */
    public static final class Cache {
        private final int[] inputs = new int[7];
        private boolean offhandOnRight;
        private Placement placement;

        public Placement place(int guiWidth, int guiHeight, int guiScale, int unitPixels, int iconWidth,
                               int magazineTextWidth, int reserveTextWidth, boolean offhandOnRight) {
            if (placement == null || inputs[0] != guiWidth || inputs[1] != guiHeight || inputs[2] != guiScale
                    || inputs[3] != unitPixels || inputs[4] != iconWidth || inputs[5] != magazineTextWidth
                    || inputs[6] != reserveTextWidth || this.offhandOnRight != offhandOnRight) {
                inputs[0] = guiWidth;
                inputs[1] = guiHeight;
                inputs[2] = guiScale;
                inputs[3] = unitPixels;
                inputs[4] = iconWidth;
                inputs[5] = magazineTextWidth;
                inputs[6] = reserveTextWidth;
                this.offhandOnRight = offhandOnRight;
                placement = AmmoHudLayout.place(guiWidth, guiHeight, guiScale, unitPixels, iconWidth,
                        magazineTextWidth, reserveTextWidth, offhandOnRight);
            }
            return placement;
        }
    }
}
