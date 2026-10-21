package dev.csmc.viewmodel;

/**
 * Geometry a primitive actually draws. glTF primitives may share one vertex accessor (an exported glove viewmodel puts
 * the left glove, right glove and bare arm over the same positions), so sizes must come from the indexed vertices only.
 */
public final class ReferencedGeometry {
    private ReferencedGeometry() {}

    /** Distinct vertices referenced by {@code indices} (out-of-range indices are ignored). */
    public static int vertexCount(int[] indices, int vertexCount) {
        boolean[] seen = new boolean[vertexCount];
        int count = 0;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount || seen[index]) continue;
            seen[index] = true;
            count++;
        }
        return count;
    }

    /**
     * Axis-aligned bounds {@code {minX, minY, minZ, maxX, maxY, maxZ}} of the xyz {@code positions} referenced by
     * {@code indices}, or null when no index is in range.
     */
    public static float[] bounds(float[] positions, int[] indices) {
        int vertices = positions.length / 3;
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (int index : indices) {
            if (index < 0 || index >= vertices) continue;
            any = true;
            for (int axis = 0; axis < 3; axis++) {
                float value = positions[index * 3 + axis];
                bounds[axis] = Math.min(bounds[axis], value);
                bounds[axis + 3] = Math.max(bounds[axis + 3], value);
            }
        }
        return any ? bounds : null;
    }
}
