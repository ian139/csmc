package dev.csmc.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.Test;

public class AmmoHudLayoutTest {
    /** Font widths of digits in the vanilla font: 6 px advance per digit, minus the trailing pixel. */
    private static int digits(int count) {
        return count * 6 - 1;
    }

    @Test
    public void iconKeepsAspectAtFixedHeightAndCapsLongSilhouettes() {
        // Trimmed alpha bounds of the exported AK-47 icon.
        assertEquals(51, AmmoHudLayout.iconWidth(702, 205));
        assertEquals(AmmoHudLayout.ICON_MAX_WIDTH, AmmoHudLayout.iconWidth(1000, 100));
        assertEquals(1, AmmoHudLayout.iconWidth(1, 1000));
        assertEquals(AmmoHudLayout.ICON_HEIGHT, AmmoHudLayout.iconWidth(0, 0));
    }

    @Test
    public void unitSizeFollowsScreenHeightWithGuiScaleAsFloor() {
        assertEquals(2, AmmoHudLayout.unitPixels(720, 2));
        assertEquals(2, AmmoHudLayout.unitPixels(720, 3));
        assertEquals(3, AmmoHudLayout.unitPixels(1080, 1));
        assertEquals(4, AmmoHudLayout.unitPixels(1440, 2));
        assertEquals(4, AmmoHudLayout.unitPixels(1440, 4));
        assertEquals(3, AmmoHudLayout.unitPixels(480, 6));
        assertEquals(1, AmmoHudLayout.unitPixels(100, 1));
    }

    @Test
    public void akReadsLikeCs2PanelIndependentOfGuiScale() {
        int[][] screens = {{1280, 720}, {1920, 1080}, {2560, 1440}};
        for (int[] screen : screens) {
            for (int gui = 1; gui <= maxGuiScale(screen); gui++) {
                int unit = AmmoHudLayout.unitPixels(screen[1], gui);
                double iconShare = AmmoHudLayout.iconWidth(702, 205) * unit / (double) screen[0];
                double digitShare = AmmoHudLayout.DIGIT_HEIGHT * AmmoHudLayout.MAGAZINE_SCALE * unit / (double) screen[1];
                assertTrue(screen[0] + "@" + gui + " icon " + iconShare, iconShare >= 0.07 && iconShare <= 0.09);
                assertTrue(screen[0] + "@" + gui + " digits " + digitShare, digitShare >= 0.03 && digitShare <= 0.04);
            }
        }
    }

    @Test
    public void neverOverlapsHotbarOffhandOrStatusBars() {
        int[][] screens = {{1280, 720}, {1920, 1080}, {2560, 1440}, {854, 480}};
        int[][] contents = {{51, digits(2), digits(2)}, {AmmoHudLayout.ICON_MAX_WIDTH, digits(3), digits(3)}};
        for (int[] screen : screens) {
            for (int gui = 1; gui <= maxGuiScale(screen); gui++) {
                int guiWidth = (int) Math.ceil(screen[0] / (double) gui);
                int guiHeight = (int) Math.ceil(screen[1] / (double) gui);
                int unit = AmmoHudLayout.unitPixels(screen[1], gui);
                for (int[] content : contents) {
                    for (boolean offhandRight : new boolean[] {false, true}) {
                        AmmoHudLayout.Placement box = AmmoHudLayout.place(guiWidth, guiHeight, gui, unit,
                                content[0], content[1], content[2], offhandRight);
                        String where = screen[0] + "x" + screen[1] + "@" + gui;
                        double right = box.x() + box.width() * box.scale();
                        double bottom = box.y() + box.height() * box.scale();
                        assertTrue(where, box.x() >= 0 && right <= guiWidth && bottom <= guiHeight);
                        int hotbarRight = guiWidth / 2 + AmmoHudLayout.HOTBAR_HALF_WIDTH
                                + (offhandRight ? AmmoHudLayout.OFFHAND_WIDTH : 0);
                        boolean beside = box.x() >= hotbarRight + AmmoHudLayout.CLEARANCE_GAP - 1e-9;
                        boolean above = bottom <= guiHeight - AmmoHudLayout.STATUS_CLEARANCE;
                        assertTrue(where, beside || above);
                    }
                }
            }
        }
    }

    @Test
    public void sitsAtMarginFromBottomRightCornerOnFramebufferPixels() {
        AmmoHudLayout.Placement box = AmmoHudLayout.place(1280, 720, 2, 4, 51, digits(2), digits(2), false);
        assertEquals(2.0, box.scale(), 0);
        assertEquals(1280 - AmmoHudLayout.MARGIN * 2.0, box.x() + box.width() * box.scale(), 0);
        assertEquals(720 - AmmoHudLayout.MARGIN * 2.0, box.y() + box.height() * box.scale(), 0);
        AmmoHudLayout.Placement thirds = AmmoHudLayout.place(427, 240, 3, 2, 51, digits(2), digits(3), false);
        assertEquals(Math.rint(thirds.x() * 3), thirds.x() * 3, 1e-9);
        assertEquals(Math.rint(thirds.y() * 3), thirds.y() * 3, 1e-9);
    }

    @Test
    public void rowElementsAreOrderedIconDividerMagazineReserve() {
        AmmoHudLayout.Placement box = AmmoHudLayout.place(640, 360, 2, 2, 30, 12, 18, false);
        assertEquals(AmmoHudLayout.PADDING, box.iconX());
        assertEquals(box.iconX() + 30 + AmmoHudLayout.GAP, box.dividerX());
        assertEquals(box.dividerX() + 1 + AmmoHudLayout.GAP, box.magazineX());
        assertEquals(box.magazineX() + 12 * AmmoHudLayout.MAGAZINE_SCALE + AmmoHudLayout.GAP / 2, box.reserveX());
        assertEquals(box.width() - AmmoHudLayout.PADDING, box.reserveX() + 18);
        assertEquals(Math.max(AmmoHudLayout.ICON_HEIGHT, AmmoHudLayout.FONT_HEIGHT * AmmoHudLayout.MAGAZINE_SCALE)
                + 2 * AmmoHudLayout.PADDING, box.height());
    }

    @Test
    public void iconNamesAreCs2ItemClassNames() {
        assertEquals("ak47", WeaponIcons.iconName("csmc:ak47"));
        assertEquals("m4a1", WeaponIcons.iconName("csmc:m4a1"));
        assertEquals("m4a1_silencer", WeaponIcons.iconName("csmc:m4a1_silencer"));
        assertEquals("usp_silencer", WeaponIcons.iconName("csmc:usp_silencer"));
        assertEquals("hkp2000", WeaponIcons.iconName("csmc:hkp2000"));
        assertEquals("revolver", WeaponIcons.iconName("revolver"));
        assertNull(WeaponIcons.iconName("csmc:../escape"));
        assertNull(WeaponIcons.iconName("csmc:"));
        assertEquals(Path.of("/c/icons/awp.png"), WeaponIcons.file(Path.of("/c"), "csmc:awp"));
        assertNull(WeaponIcons.file(Path.of("/c"), "csmc:a/b"));
    }

    /** Vanilla's largest GUI scale: each GUI pixel must leave at least 320x240 of them. */
    private static int maxGuiScale(int[] screen) {
        int scale = 1;
        while (screen[0] / (scale + 1) >= 320 && screen[1] / (scale + 1) >= 240) scale++;
        return scale;
    }
}
