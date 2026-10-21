package dev.csmc.surf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.csmc.movement.geometry.BrushPlane;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import org.junit.Test;

public final class SurfRampsCanonicalTest {
    @Test
    public void canonicalisingTwiceIsANoOp() {
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            BrushPlane plane = BrushPlane.of(random.nextGaussian(), random.nextGaussian(), random.nextGaussian(),
                    (random.nextDouble() - 0.5) * 1.0e6);
            BrushPlane once = SurfRamps.canonicalPlane(plane);
            BrushPlane twice = SurfRamps.canonicalPlane(once);
            assertEquals(once, twice);
            assertBitIdentical(once, twice);
            assertEquals(once, SurfRamps.translateCanonical(once, 0, 0, 0));
        }
    }

    @Test
    public void mapLocalCanonicalPlaneTranslatesIdenticallyAtAnyOrigin() {
        Random random = new Random(11);
        int[] originsX = {0, 10_000_000, -10_000_000, 29_999_984};
        for (int i = 0; i < 500; i++) {
            // A slightly irrational map-local plane (as an importer would produce from a VMF).
            BrushPlane mapLocal = BrushPlane.of(random.nextGaussian(), random.nextGaussian(), random.nextGaussian(),
                    (random.nextDouble() - 0.5) * 4096);
            BrushPlane canonical = SurfRamps.canonicalPlane(mapLocal);
            BlockPos cell = new BlockPos(random.nextInt(512) - 256, random.nextInt(256) - 64,
                    random.nextInt(512) - 256);
            List<BrushPlane> referenceLocal = SurfRamps.toLocal(List.of(canonical), cell);
            BrushPlane atZero = SurfRamps.translateCanonical(canonical, 0, 0, 0);
            for (int originX : originsX) {
                BrushPlane world = SurfRamps.translateCanonical(canonical, originX, 64, -originX / 2);
                assertEquals(Double.doubleToRawLongBits(canonical.nx()), Double.doubleToRawLongBits(world.nx()));
                assertEquals(Double.doubleToRawLongBits(canonical.ny()), Double.doubleToRawLongBits(world.ny()));
                assertEquals(Double.doubleToRawLongBits(canonical.nz()), Double.doubleToRawLongBits(world.nz()));
                double expectedShift = canonical.nx() * originX + canonical.ny() * 64
                        + canonical.nz() * (-originX / 2);
                assertTrue("distance drift at x=" + originX,
                        Math.abs(world.distance() - atZero.distance() - expectedShift) < 1.0e-9);
                // The translated world plane is already canonical, and the cell sees the same local plane.
                assertBitIdentical(world, SurfRamps.canonicalPlane(world));
                BlockPos worldCell = cell.offset(originX, 64, -originX / 2);
                List<BrushPlane> local = SurfRamps.toLocal(List.of(world), worldCell);
                assertEquals(referenceLocal, local);
                assertBitIdentical(referenceLocal.get(0), local.get(0));
            }
        }
    }

    private static void assertBitIdentical(BrushPlane expected, BrushPlane actual) {
        assertEquals(Double.doubleToRawLongBits(expected.nx()), Double.doubleToRawLongBits(actual.nx()));
        assertEquals(Double.doubleToRawLongBits(expected.ny()), Double.doubleToRawLongBits(actual.ny()));
        assertEquals(Double.doubleToRawLongBits(expected.nz()), Double.doubleToRawLongBits(actual.nz()));
        assertEquals(Double.doubleToRawLongBits(expected.distance()), Double.doubleToRawLongBits(actual.distance()));
    }
}
