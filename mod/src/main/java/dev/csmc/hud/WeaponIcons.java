package dev.csmc.hud;

import java.nio.file.Path;

/**
 * Maps csmc weapon ids to CS2 equipment icon names ({@code panorama/images/icons/equipment/<name>.vsvg_c}),
 * exported by {@code tools/export-cs2-assets --icons} as {@code <cache>/icons/<name>.png}. csmc ids already are
 * CS2 item class names (m4a1 = M4A4, m4a1_silencer, usp_silencer, hkp2000, revolver), so the name is the id path.
 */
public final class WeaponIcons {
    private WeaponIcons() {}

    /** CS2 icon name for a weapon id (namespace dropped), or null if it is not a safe file name. */
    public static String iconName(String weaponId) {
        if (weaponId == null) return null;
        String path = weaponId.substring(weaponId.indexOf(':') + 1);
        return path.matches("[a-z0-9_]+") ? path : null;
    }

    public static Path file(Path cacheRoot, String weaponId) {
        String name = iconName(weaponId);
        return name == null ? null : cacheRoot.resolve("icons").resolve(name + ".png");
    }
}
