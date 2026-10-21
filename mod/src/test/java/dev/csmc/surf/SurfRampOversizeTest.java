package dev.csmc.surf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.AbstractList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;

public final class SurfRampOversizeTest {
    /** Distinct valid planes {@code n·p <= d} with slightly different tilts; get() counts accesses. */
    private static final class CountingPlanes extends AbstractList<BrushPlane> {
        private final int size;
        int reads;

        CountingPlanes(int size) {
            this.size = size;
        }

        @Override
        public BrushPlane get(int index) {
            reads++;
            return BrushPlane.of(0, 1, 1 + index * 1.0e-3, 1.0);
        }

        @Override
        public int size() {
            return size;
        }
    }

    @Test
    public void validateStopsAtTheFifthDistinctPlane() {
        CountingPlanes planes = new CountingPlanes(1_000_000);
        assertNull(SurfRamps.validate(planes));
        assertEquals(5, planes.reads);
    }

    @Test
    public void validateStillMergesDuplicatesBeyondFourEntries() {
        BrushPlane plane = BrushPlane.of(0, 1, 2, 2);
        assertNotNull(SurfRamps.validate(List.of(plane, plane, plane, plane, plane, plane)));
    }

    @Test
    public void persistedListsOutsideOneToFourAreRejectedBeforeDecoding() {
        assertNull(SurfRampBlockEntity.decode(new ListTag()));
        // Entries that would throw if decoded (non-unit normals): rejection must happen on size alone.
        ListTag oversized = new ListTag();
        for (int i = 0; i < 100_000; i++) oversized.add(new CompoundTag());
        assertNull(SurfRampBlockEntity.decode(oversized));
        ListTag one = new ListTag();
        CompoundTag entry = new CompoundTag();
        BrushPlane plane = SurfRamps.canonicalPlane(BrushPlane.of(0, 1, 2, 2));
        entry.putDouble("nx", plane.nx());
        entry.putDouble("ny", plane.ny());
        entry.putDouble("nz", plane.nz());
        entry.putDouble("d", plane.distance());
        one.add(entry);
        RampGeometry decoded = SurfRampBlockEntity.decode(one);
        assertNotNull(decoded);
        assertTrue(decoded.planes().equals(List.of(plane)));
    }
}
