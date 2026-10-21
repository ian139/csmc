package dev.csmc.viewmodel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReferencedGeometryTest {
    /** One shared accessor: a small glove (0..2) near the origin and a far, large bare arm (3..5). */
    private static final float[] SHARED = {
            0, 0, 0, 1, 0, 0, 0, 2, 0,
            100, 0, 0, 140, 0, 0, 100, 0, -50};

    @Test
    public void boundsCoverOnlyTheIndexedVertices() {
        assertArrayEquals(new float[] {0, 0, 0, 1, 2, 0}, ReferencedGeometry.bounds(SHARED, new int[] {0, 1, 2}), 0);
        assertArrayEquals(new float[] {100, 0, -50, 140, 0, 0},
                ReferencedGeometry.bounds(SHARED, new int[] {3, 4, 5, 5, 4, 3}), 0);
    }

    @Test
    public void noIndexedVertexHasNoBounds() {
        assertNull(ReferencedGeometry.bounds(SHARED, new int[0]));
        assertNull(ReferencedGeometry.bounds(SHARED, new int[] {-1, 6}));
    }

    @Test
    public void vertexCountIgnoresTheSharedAccessorSize() {
        assertEquals(3, ReferencedGeometry.vertexCount(new int[] {0, 1, 2, 2, 1, 0}, 6));
        assertEquals(4, ReferencedGeometry.vertexCount(new int[] {0, 1, 2, 2, 3, 0, 9}, 6));
        assertEquals(0, ReferencedGeometry.vertexCount(new int[0], 6));
    }
}
