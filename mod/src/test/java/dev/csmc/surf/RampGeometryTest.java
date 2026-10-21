package dev.csmc.surf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

public final class RampGeometryTest {
    private static final double TOLERANCE = 1.0E-12;

    @Test
    public void defaultRampIsTwoToOneWedgeRisingNorth() {
        RampGeometry geometry = SurfRamps.defaultGeometry();
        // y + 2z <= 2 inside the unit cell: the area at height y is 1 - y/2, so the volume is 3/4.
        // Canonical normals sit on a 2^-24 grid, so the plane is tilted by at most ~1e-7.
        assertEquals(0.75, geometry.volume(), 1.0E-6);
        BrushPlane plane = geometry.planes().get(0);
        assertEquals(1 / Math.sqrt(5), plane.ny(), 1.0E-7);
        assertEquals(2 / Math.sqrt(5), plane.nz(), 1.0E-7);
        assertEquals(0.0, plane.nx(), 0.0);
        List<Direction> culled = new ArrayList<>();
        int sloped = 0;
        for (RampGeometry.Polygon polygon : geometry.polygons()) {
            if (polygon.cullFace() != null) culled.add(polygon.cullFace());
            if (Math.abs(polygon.nz() - plane.nz()) < 1.0E-6 && Math.abs(polygon.ny() - plane.ny()) < 1.0E-6) sloped++;
        }
        assertEquals(1, sloped);
        // Full faces: bottom and north (z = 0). Top (half), east/west (trapezoids) are partial; south is gone.
        assertEquals(List.of(Direction.DOWN, Direction.NORTH), culled);
    }

    @Test
    public void validateRejectsEmptyTooManyAndDeduplicates() {
        assertNull(SurfRamps.validate(List.of()));
        assertNull("solid below the cell", SurfRamps.validate(List.of(BrushPlane.of(0, 1, 0, -0.5))));
        assertNull("sliver thinner than the minimum volume",
                SurfRamps.validate(List.of(BrushPlane.of(0, 1, 0, 1.0E-7))));
        List<BrushPlane> five = List.of(BrushPlane.of(1, 1, 0, 1.5), BrushPlane.of(-1, 1, 0, 0.5),
                BrushPlane.of(0, 1, 1, 1.5), BrushPlane.of(0, 1, -1, 0.5), BrushPlane.of(0, 1, 0, 0.9));
        assertNull(SurfRamps.validate(five));
        RampGeometry four = SurfRamps.validate(five.subList(0, 4));
        assertNotNull(four);
        assertEquals(4, four.planes().size());
        RampGeometry deduped = SurfRamps.validate(List.of(BrushPlane.of(0, 1, 2, 2), BrushPlane.of(0, 2, 4, 4),
                BrushPlane.of(0, 1, 2, 2 + 1.0E-12)));
        assertNotNull(deduped);
        assertEquals(1, deduped.planes().size());
        RampGeometry full = SurfRamps.validate(List.of(BrushPlane.of(0, 1, 0, 3)));
        assertNotNull(full);
        assertTrue(full.isFullCell());
    }

    @Test
    public void inscribedStaircaseNeverPokesAboveTheSurface() {
        Random random = new Random(1234);
        int checked = 0;
        for (int trial = 0; trial < 400; trial++) {
            List<BrushPlane> planes = new ArrayList<>();
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                planes.add(BrushPlane.of(random.nextGaussian(), random.nextGaussian() + 0.5, random.nextGaussian(),
                        random.nextDouble() * 1.5 - 0.25));
            }
            RampGeometry geometry = SurfRamps.validate(planes);
            if (geometry == null) continue;
            for (AABB box : geometry.inscribedCollision().toAabbs()) {
                for (int corner = 0; corner < 8; corner++) {
                    double x = (corner & 1) == 0 ? box.minX : box.maxX;
                    double y = (corner & 2) == 0 ? box.minY : box.maxY;
                    double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
                    for (BrushPlane plane : geometry.planes()) {
                        assertTrue("staircase corner outside " + plane, plane.signedDistance(x, y, z) <= TOLERANCE);
                    }
                    assertTrue(x >= 0 && x <= 1 && y >= 0 && y <= 1 && z >= 0 && z <= 1);
                    checked++;
                }
            }
            // The outline covers every vertex of the exact solid.
            for (RampGeometry.Polygon polygon : geometry.polygons()) {
                for (float[] v : polygon.vertices()) {
                    boolean covered = geometry.outline().toAabbs().stream().anyMatch(b -> b.inflate(1.0E-5)
                            .contains(v[0], v[1], v[2]));
                    assertTrue("outline misses vertex", covered);
                }
            }
        }
        assertTrue(checked > 1000);
    }

    @Test
    public void staircaseApproachesTheSurface() {
        // A 2:1 slope: the staircase loses at most one 1/8 column of run (1/4 block of height) plus 1/64 rounding.
        RampGeometry geometry = SurfRamps.defaultGeometry();
        double collisionVolume = geometry.inscribedCollision().toAabbs().stream()
                .mapToDouble(b -> b.getXsize() * b.getYsize() * b.getZsize()).sum();
        assertTrue(collisionVolume > 0.75 - 0.15);
        assertTrue(collisionVolume < 0.75);
    }

    @Test
    public void builderPlanesTranslateBitExactlyIntoEveryCell() {
        BoundingBox box = new BoundingBox(-29_999_000, 60, 12_345_678, -29_998_990, 80, 12_345_690);
        for (Direction upslope : Direction.Plane.HORIZONTAL) {
            BrushPlane world = SurfBuilder.rampPlanes(box, upslope, 61.7).get(0);
            assertEquals(world, SurfRamps.canonicalPlane(world));
            for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                    box.maxZ())) {
                BrushPlane local = SurfRamps.toLocal(List.of(world), pos).get(0);
                BrushPlane back = SurfRamps.toWorld(local, pos);
                assertEquals(Double.doubleToRawLongBits(world.distance()), Double.doubleToRawLongBits(back.distance()));
                assertEquals(world, back);
            }
        }
    }

    @Test
    public void rampRisesFromTheLowEdgeAtTheRequestedAngle() {
        BoundingBox box = new BoundingBox(10, 5, 20, 13, 9, 21);
        BrushPlane east = SurfBuilder.rampPlanes(box, Direction.EAST, 45).get(0);
        assertEquals(0.0, east.signedDistance(10, 5, 20.5), 1.0E-6);
        assertEquals(0.0, east.signedDistance(13, 8, 20.5), 1.0E-6);
        assertTrue(east.signedDistance(13, 7, 20.5) < 0);
        BrushPlane west = SurfBuilder.rampPlanes(box, Direction.WEST, 45).get(0);
        assertEquals(0.0, west.signedDistance(14, 5, 21), 1.0E-6);
        assertEquals(0.0, west.signedDistance(11, 8, 21), 1.0E-6);
    }

    @Test
    public void ridgeHasTwoMirroredPlanesMeetingOnTheCentreLine() {
        BoundingBox box = new BoundingBox(0, 0, 0, 7, 4, 3);
        List<BrushPlane> ridge = SurfBuilder.ridgePlanes(box, Direction.Axis.X, 60);
        assertEquals(2, ridge.size());
        double crestY = Math.tan(Math.toRadians(60)) * 2.0;
        for (BrushPlane plane : ridge) assertEquals(0.0, plane.signedDistance(3.5, crestY, 2.0), 1.0E-6);
        assertEquals(ridge.get(0).nz(), -ridge.get(1).nz(), 0.0);
        assertEquals(ridge.get(0).ny(), ridge.get(1).ny(), 0.0);
    }

    @Test
    public void providerBrushIsCellPlusTranslatedPlanes() {
        RampGeometry geometry = SurfRamps.defaultGeometry();
        BlockPos pos = new BlockPos(-7, 70, 3);
        ConvexBrush brush = SurfRampGeometryProvider.brush(geometry, pos);
        assertEquals(geometry.planes().size() + 6, brush.planes().size());
        assertEquals(SurfRamps.toWorld(geometry.planes().get(0), pos), brush.planes().get(0));
        // Centre of the solid part is inside; a point above the slope (inside the cell) is outside.
        assertTrue(brush.planes().stream().allMatch(p -> p.signedDistance(-6.5, 70.2, 3.1) <= 0));
        assertFalse(brush.planes().stream().allMatch(p -> p.signedDistance(-6.5, 70.9, 3.9) <= 0));
        assertEquals(new AABB(-7, 70, 3, -6, 71, 4), brush.bounds());
    }
}
