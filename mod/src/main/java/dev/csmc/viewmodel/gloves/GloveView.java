package dev.csmc.viewmodel.gloves;

/**
 * Pure rules for showing a worn glove: which arms primitives it replaces or paints, and where its slot sits in the
 * creative inventory's survival tab. No Minecraft client dependency, so it is unit-tested.
 */
public final class GloveView {
    /** The CS2 arm-skin material prefix ({@code bare_arm_*}); everything else in a glove viewmodel mesh is glove. */
    public static final String BARE_ARM_PREFIX = "bare_arm";
    /** Manifest role of the team arms mesh that carries the default gloves; a worn glove replaces it. */
    public static final String DEFAULT_GLOVES_ROLE = "arms_gloves";
    /**
     * Creative survival-tab slot position. Vanilla places menu slots past the offhand onto the hotbar row; the glove
     * slot goes in the empty column left of the offhand slot (35, 20) instead.
     */
    public static final int CREATIVE_X = 9;
    public static final int CREATIVE_Y = 20;

    private GloveView() {}

    /** Whether a glove viewmodel primitive with this texture/material name takes the finish (not the bare arm). */
    public static boolean paintable(String textureName) {
        return textureName == null || !textureName.startsWith(BARE_ARM_PREFIX);
    }

    /** Whether the team arms mesh with {@code role} is hidden while a glove mesh is drawn in its place. */
    public static boolean replacedByGlove(String role) {
        return DEFAULT_GLOVES_ROLE.equals(role);
    }

    /** Creative survival-tab X for menu slot {@code index}: the glove slot moves, every other slot keeps vanilla's. */
    public static int creativeX(int index, int gloveIndex, int vanillaX) {
        return index == gloveIndex ? CREATIVE_X : vanillaX;
    }

    /** Creative survival-tab Y counterpart of {@link #creativeX}. */
    public static int creativeY(int index, int gloveIndex, int vanillaY) {
        return index == gloveIndex ? CREATIVE_Y : vanillaY;
    }

    /**
     * The glove id whose arms to draw: the worn glove's when its asset is usable, else null (the team's default
     * gloves). {@code usable} reports whether the cache holds a loadable glove mesh for an id.
     */
    public static String armsGlove(String wornGloveId, java.util.function.Predicate<String> usable) {
        return wornGloveId != null && usable.test(wornGloveId) ? wornGloveId : null;
    }
}
