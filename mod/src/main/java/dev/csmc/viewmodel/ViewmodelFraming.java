package dev.csmc.viewmodel;

import org.joml.Matrix4f;

/**
 * CS2 first-person conventions for exported viewmodel clips. Source's X-forward/Y-left/Z-up becomes glTF's
 * Z-forward/X-left/Y-up in the export; Minecraft view space is -Z-forward/X-right/Y-up. There is no camera joint in
 * the clip skeleton: root_motion is the eye-space origin, wpn carries the weapon, and the remaining chains are arms.
 *
 * <p>The dumped CS2 convar defaults are viewmodel_fov 60 (68 is the maximum), offset_x 1, offset_y 1, offset_z -1.
 * FOV is horizontal at 4:3, widened at wider aspects (hor+), making the default vertical FOV about 46.8 degrees.
 * Offsets are Source inches along right/forward/up. A dedicated projection, rather than scaling the model's vertices,
 * leaves camera headings, normals, lighting and the user's viewmodel size/offset adjustments unaffected.
 */
public final class ViewmodelFraming {
    public static final float VIEWMODEL_FOV = 60;
    /** CS2 default viewmodel offsets in metres, right / forward / up (1 inch = 0.0254 m). */
    public static final float OFFSET_RIGHT = .0254f;
    public static final float OFFSET_FORWARD = .0254f;
    public static final float OFFSET_UP = -.0254f;
    private static final float VERTICAL_FOCAL_LENGTH =
            (float) (1 / (Math.tan(Math.toRadians(VIEWMODEL_FOV) / 2) * .75));

    private ViewmodelFraming() {}

    /** Vertical field of view in radians for a horizontal 4:3 field of view in degrees. */
    public static double verticalFov(float horizontal43Degrees) {
        return 2 * Math.atan(Math.tan(Math.toRadians(horizontal43Degrees) / 2) * .75);
    }

    /**
     * Replaces only a perspective projection's FOV: fixed CS2 vertical angle with the existing aspect and depth range.
     * The caller flushes buffered geometry before changing it, then restores m00/m11 after flushing the viewmodel.
     * Mutates and returns {@code projection}; no allocation or trigonometry per frame.
     */
    public static Matrix4f applyProjection(Matrix4f projection) {
        float inverseAspect = projection.m00() / projection.m11();
        return projection.m00(VERTICAL_FOCAL_LENGTH * inverseAspect).m11(VERTICAL_FOCAL_LENGTH);
    }

    /** Exported glTF viewmodel space → Minecraft view space, reflected across view X for the left hand. */
    public static Matrix4f clipToView(boolean left, Matrix4f out) {
        return out.scaling(left ? 1 : -1, 1, -1);
    }
}
