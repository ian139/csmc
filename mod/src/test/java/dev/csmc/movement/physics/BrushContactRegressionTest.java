package dev.csmc.movement.physics;

import static dev.csmc.movement.physics.MovementFixtures.HALF_WIDTH;
import static dev.csmc.movement.physics.MovementFixtures.HEIGHT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

/**
 * Seam and normal-precision regressions for {@link ConvexBrushWorld}: a solid split into block cells must collide
 * exactly like the unsplit solid (earliest true contact wins across brushes; coincident contacts never report a
 * hidden cell constraint), and slightly over-length normals (2^-24 grid canonicalisation) must not suppress bevels.
 */
public class BrushContactRegressionTest {
    private static final double DT = 1.0 / 60.0;

    private static ConvexBrush clipped(BrushPlane first, AABB box) {
        List<BrushPlane> planes = new ArrayList<>();
        planes.add(first);
        planes.addAll(ConvexBrush.box(box).planes());
        return new ConvexBrush(planes, box);
    }

    /** Lands with a fast fall and horizontal speed; returns the final state after one COMPETITIVE substep. */
    private static CsPlayerState land(List<ConvexBrush> brushes) {
        CsPlayerState state = new CsPlayerState();
        state.origin.set(-12.025, 0.2, 0);
        state.velocity.set(250, -3490, 0);
        CsGameMovement.step(state, MovementFixtures.input(0, 0, false, 0), CsMovementParams.COMPETITIVE,
                new ConvexBrushWorld(brushes), DT);
        return state;
    }

    private static CsTrace landingTrace(List<ConvexBrush> brushes) {
        CsTrace tr = new CsTrace();
        new ConvexBrushWorld(brushes).traceHull(-12.025, 0.2, 0, -12.025 + 250 * DT, 0.2 - 3490 * DT, 0,
                HALF_WIDTH, HEIGHT, tr);
        return tr;
    }

    @Test
    public void landingAcrossAnInternalCellSeamKeepsHorizontalSpeedLikeTheUnsplitFloor() {
        List<ConvexBrush> split = List.of(MovementFixtures.box(-1, -1, -1, 0, 0, 1),
                MovementFixtures.box(0, -1, -1, 1, 0, 1));
        List<ConvexBrush> unsplit = List.of(MovementFixtures.box(-1, -1, -1, 1, 0, 1));

        CsTrace splitTrace = landingTrace(split);
        CsTrace unsplitTrace = landingTrace(unsplit);
        assertEquals(1.0, unsplitTrace.normal.y, 1e-12);
        assertEquals(unsplitTrace.normal.x, splitTrace.normal.x, 1e-12);
        assertEquals(unsplitTrace.normal.y, splitTrace.normal.y, 1e-12);
        assertEquals(unsplitTrace.fraction, splitTrace.fraction, 1e-9);

        CsPlayerState u = land(unsplit);
        CsPlayerState s = land(split);
        assertEquals(250.0, u.velocity.x, 1e-6);
        assertEquals(0.0, u.velocity.y, 1e-9);
        assertEquals(250.0, s.velocity.x, 1e-6);
        assertEquals(0.0, s.velocity.y, 1e-9);
        assertEquals(0.0, s.velocity.z, 1e-9);
        assertEquals(u.origin.x, s.origin.x, 1e-6);
        assertEquals(u.origin.y, s.origin.y, 1e-6);
        assertTrue(s.grounded);
    }

    @Test
    public void coincidentSeamContactReportsTheRampNormalNotAHiddenCellFace() {
        BrushPlane ramp = BrushPlane.of(-0.8, 0.6, 0, 0);
        List<ConvexBrush> split = List.of(clipped(ramp, new AABB(-1, -2, 0, 0, 2, 1)),
                clipped(ramp, new AABB(0, -2, 0, 1, 2, 1)));
        List<ConvexBrush> unsplit = List.of(clipped(ramp, new AABB(-1, -2, 0, 1, 2, 1)));

        CsTrace s = new CsTrace();
        CsTrace u = new CsTrace();
        new ConvexBrushWorld(split).traceHull(-12.8, 0.6, 20, -11.2, -0.6, 20, HALF_WIDTH, HEIGHT, s);
        new ConvexBrushWorld(unsplit).traceHull(-12.8, 0.6, 20, -11.2, -0.6, 20, HALF_WIDTH, HEIGHT, u);

        assertEquals((1.0 - ConvexBrushWorld.DIST_EPSILON) / 2.0, u.fraction, 1e-5);
        assertEquals(-0.8, u.normal.x, 1e-12);
        assertEquals(0.6, u.normal.y, 1e-12);
        assertEquals(u.fraction, s.fraction, 1e-9);
        assertEquals(-0.8, s.normal.x, 1e-12);
        assertEquals(0.6, s.normal.y, 1e-12);
        assertEquals(0.0, s.normal.z, 1e-12);
        assertFalse(s.startSolid);
    }

    @Test
    public void reviewedFloorTraceRejectsTheMoreOpposedInternalFace() {
        assertReviewedFloorTrace(2, 1);
    }

    @Test
    public void reviewedEqualOppositionTraceIsIndependentOfBrushOrder() {
        assertReviewedFloorTrace(1, 1);
    }

    private static void assertReviewedFloorTrace(double dx, double dy) {
        ConvexBrush left = MovementFixtures.box(-1, -1, -1, 0, 0, 1);
        ConvexBrush right = MovementFixtures.box(0, -1, -1, 1, 0, 1);
        List<ConvexBrush> whole = List.of(MovementFixtures.box(-1, -1, -1, 1, 0, 1));
        assertUnionEquivalent(whole, List.of(left, right), -12, 0, 0, dx, -dy, 0);
        assertUnionEquivalent(whole, List.of(right, left), -12, 0, 0, dx, -dy, 0);
    }

    @Test
    public void reviewedRampTraceRejectsTheHiddenHorizontalBevel() {
        BrushPlane ramp = BrushPlane.of(-0.8, 0.6, 0, 0);
        ConvexBrush left = clipped(ramp, new AABB(-1, -2, 0, 0, 2, 1));
        ConvexBrush right = clipped(ramp, new AABB(0, -2, 0, 1, 2, 1));
        List<ConvexBrush> whole = List.of(clipped(ramp, new AABB(-1, -2, 0, 1, 2, 1)));
        assertUnionEquivalent(whole, List.of(left, right), -12, 0, 20, 0.8, -3, 0);
        assertUnionEquivalent(whole, List.of(right, left), -12, 0, 20, 0.8, -3, 0);
    }

    @Test
    public void floorUnionBoundaryWinsForEveryApproachRatioAndBrushOrder() {
        ConvexBrush left = MovementFixtures.box(-1, -1, -1, 0, 0, 1);
        ConvexBrush right = MovementFixtures.box(0, -1, -1, 1, 0, 1);
        List<ConvexBrush> whole = List.of(MovementFixtures.box(-1, -1, -1, 1, 0, 1));
        for (double dx : new double[] {0.125, 0.5, 1, 2, 4, 8, 16}) {
            for (double dy : new double[] {0.125, 0.5, 1, 2, 4, 16, 64}) {
                assertUnionEquivalent(whole, List.of(left, right), -12, 0, 0, dx, -dy, 0);
                assertUnionEquivalent(whole, List.of(right, left), -12, 0, 0, dx, -dy, 0);
            }
        }
    }

    @Test
    public void reviewedFloorLandingRetains240UnitsPerSecondInBothBrushOrders() {
        ConvexBrush left = MovementFixtures.box(-1, -1, -1, 0, 0, 1);
        ConvexBrush right = MovementFixtures.box(0, -1, -1, 1, 0, 1);
        for (List<ConvexBrush> brushes : List.of(List.of(left, right), List.of(right, left))) {
            CsPlayerState state = new CsPlayerState();
            state.origin.set(-14, 1, 0);
            state.velocity.set(240, -113.33333333333333, 0);
            CsGameMovement.step(state, MovementFixtures.input(0, 0, false, 0), CsMovementParams.COMPETITIVE,
                    new ConvexBrushWorld(brushes), DT);
            assertEquals(240, state.velocity.x, 1e-9);
            assertEquals(0, state.velocity.y, 1e-9);
            assertTrue(state.grounded);
        }
    }

    @Test
    public void clippedRampUnionBoundaryWinsForEveryApproachRatioAndBrushOrder() {
        BrushPlane ramp = BrushPlane.of(-0.8, 0.6, 0, 0);
        ConvexBrush left = clipped(ramp, new AABB(-1, -2, 0, 0, 2, 1));
        ConvexBrush right = clipped(ramp, new AABB(0, -2, 0, 1, 2, 1));
        List<ConvexBrush> whole = List.of(clipped(ramp, new AABB(-1, -2, 0, 1, 2, 1)));
        for (double dx : new double[] {0.1, 0.4, 0.8, 1.6, 4, 12}) {
            for (double dy : new double[] {0.1, 0.6, 1, 3, 12, 64}) {
                assertUnionEquivalent(whole, List.of(left, right), -12, 0, 20, dx, -dy, 0);
                assertUnionEquivalent(whole, List.of(right, left), -12, 0, 20, dx, -dy, 0);
            }
        }
    }

    @Test
    public void rotatedClippedUnionsResolveTheirBoundaryAtLargeCoordinates() {
        for (double yaw : new double[] {23, 67, 131}) {
            MovementFixtures.Frame frame = new MovementFixtures.Frame(yaw, 100000.25, 64, -100000.5);
            List<BrushPlane> planes = new ArrayList<>();
            planes.add(frame.plane(-0.8, 0.6, 0, 0));
            for (BrushPlane p : MovementFixtures.box(-1, -2, 0, 1, 2, 1).planes()) {
                planes.add(frame.plane(p.nx(), p.ny(), p.nz(), p.distance()));
            }
            List<BrushPlane> leftPlanes = new ArrayList<>(planes);
            leftPlanes.add(frame.plane(1, 0, 0, 0));
            List<BrushPlane> rightPlanes = new ArrayList<>(planes);
            rightPlanes.add(frame.plane(-1, 0, 0, 0));
            ConvexBrush left = MovementFixtures.brush(leftPlanes, 0);
            ConvexBrush right = MovementFixtures.brush(rightPlanes, 0);
            List<ConvexBrush> whole = List.of(MovementFixtures.brush(planes, 0));
            double[] point = frame.point(0, 0, 0.5);
            double[] normal = frame.dir(-0.8, 0.6, 0);
            double qx = point[0] * 40 + Math.copySign(HALF_WIDTH, normal[0]);
            double qy = point[1] * 40;
            double qz = point[2] * 40 + Math.copySign(HALF_WIDTH, normal[2]);
            for (double dy : new double[] {0.1, 0.6, 1, 3, 12, 64}) {
                double[] delta = frame.dir(0.8, -dy, 0);
                assertUnionEquivalent(whole, List.of(left, right), qx, qy, qz, delta[0], delta[1], delta[2]);
                assertUnionEquivalent(whole, List.of(right, left), qx, qy, qz, delta[0], delta[1], delta[2]);
            }
        }
    }

    @Test
    public void severalObliquelyClippedNeighborsCoverHiddenFacetsTogether() {
        for (double yaw : new double[] {0, 29, 113}) {
            MovementFixtures.Frame frame = new MovementFixtures.Frame(yaw, 5, 3, -7);
            List<BrushPlane> wholePlanes = new ArrayList<>();
            wholePlanes.add(frame.plane(-0.71, 0.58, -0.4, 0));
            for (BrushPlane p : MovementFixtures.box(-1, -2, -1, 1, 2, 1).planes()) {
                wholePlanes.add(frame.plane(p.nx(), p.ny(), p.nz(), p.distance()));
            }
            List<ConvexBrush> split = new ArrayList<>();
            for (int x : new int[] {-1, 1}) {
                for (int z : new int[] {-1, 1}) {
                    List<BrushPlane> cell = new ArrayList<>(wholePlanes);
                    cell.add(frame.plane(x * 0.7, x * 0.2, x * 0.4, 0));
                    cell.add(frame.plane(z * -0.2, z * 0.9, z * 0.3, 0));
                    split.add(MovementFixtures.brush(cell, 0));
                }
            }
            List<ConvexBrush> whole = List.of(MovementFixtures.brush(wholePlanes, 0));
            double[] point = frame.point(0, 0, 0);
            double[] normal = frame.dir(-0.71, 0.58, -0.4);
            double qx = point[0] * 40 + Math.copySign(HALF_WIDTH, normal[0]);
            double qy = point[1] * 40;
            double qz = point[2] * 40 + Math.copySign(HALF_WIDTH, normal[2]);
            for (double dy : new double[] {0.1, 0.6, 1, 3, 12, 64}) {
                double[] delta = frame.dir(0.8, -dy, 0.2);
                assertUnionEquivalent(whole, split, qx, qy, qz, delta[0], delta[1], delta[2]);
                assertUnionEquivalent(whole, List.of(split.get(3), split.get(2), split.get(1), split.get(0)),
                        qx, qy, qz, delta[0], delta[1], delta[2]);
            }
        }
    }

    @Test
    public void deterministicSweepsMatchWholeRotatedCellUnions() {
        for (double offset : new double[] {0, 100000}) {
            MovementFixtures.Frame frame = new MovementFixtures.Frame(37, offset + 0.25, 64, -offset - 0.5);
            List<BrushPlane> planes = MovementFixtures.rampPlanes(frame, 60, 2, 0, 2);
            List<ConvexBrush> cells = MovementFixtures.splitIntoCells(planes);
            ConvexBrushWorld whole = new ConvexBrushWorld(List.of(MovementFixtures.brush(planes, 0)));
            ConvexBrushWorld split = new ConvexBrushWorld(cells);
            List<ConvexBrush> reversed = new ArrayList<>(cells.reversed());
            ConvexBrushWorld reverse = new ConvexBrushWorld(reversed);
            Random random = new Random(8675309);
            int hits = 0;
            int escapes = 0;
            for (int i = 0; i < 1000; i++) {
                double sx = (offset + 0.25) * 40 + (random.nextDouble() - 0.5) * 240;
                double sy = 64 * 40 + (random.nextDouble() - 0.5) * 240 - HEIGHT / 2;
                double sz = (-offset - 0.5) * 40 + (random.nextDouble() - 0.5) * 240;
                double ex = (offset + 0.25) * 40 + (random.nextDouble() - 0.5) * 240;
                double ey = 64 * 40 + (random.nextDouble() - 0.5) * 240 - HEIGHT / 2;
                double ez = (-offset - 0.5) * 40 + (random.nextDouble() - 0.5) * 240;
                CsTrace expected = new CsTrace();
                whole.traceHull(sx, sy, sz, ex, ey, ez, HALF_WIDTH, HEIGHT, expected);
                // allSolid deliberately retains the original per-brush semantics; an embedded start that leaves the
                // whole solid must escape the split one identically (fraction 1, no hidden cell-face hit).
                if (expected.allSolid) continue;
                if (expected.startSolid) escapes++;
                else if (expected.hit()) hits++;
                for (ConvexBrushWorld world : List.of(split, reverse)) {
                    CsTrace actual = new CsTrace();
                    world.traceHull(sx, sy, sz, ex, ey, ez, HALF_WIDTH, HEIGHT, actual);
                    assertEquals("sweep " + i, expected.fraction, actual.fraction, 1e-8);
                    assertEquals("sweep " + i, expected.normal.x, actual.normal.x, 1e-9);
                    assertEquals("sweep " + i, expected.normal.y, actual.normal.y, 1e-9);
                    assertEquals("sweep " + i, expected.normal.z, actual.normal.z, 1e-9);
                    assertEquals(expected.startSolid, actual.startSolid);
                    assertEquals(expected.allSolid, actual.allSolid);
                }
            }
            assertTrue("must exercise collisions: " + hits, hits > 100);
            assertTrue("must exercise embedded escapes: " + escapes, escapes > 20);
        }
    }

    /** Review B37: a hull embedded in the left cell escapes through the right cell, never hitting the shared face. */
    @Test
    public void embeddedStartEscapesTheSplitFloorLikeTheUnsplitFloor() {
        ConvexBrush left = MovementFixtures.box(-1, -1, -1, 0, 0, 1);
        ConvexBrush right = MovementFixtures.box(0, -1, -1, 1, 0, 1);
        CsTrace expected = trace(List.of(MovementFixtures.box(-1, -1, -1, 1, 0, 1)), -20, -1, 0, 80, -1, 0);
        assertEquals(1.0, expected.fraction, 0.0);
        assertTrue(expected.startSolid);
        assertFalse(expected.allSolid);
        for (List<ConvexBrush> split : List.of(List.of(left, right), List.of(right, left))) {
            assertSameTrace("escape", expected, trace(split, -20, -1, 0, 80, -1, 0));
        }
    }

    /** Discarding the hidden cell entry must not discard a later genuine entry into another solid. */
    @Test
    public void escapingAnEmbeddedStartStillHitsTheNextSolid() {
        ConvexBrush left = MovementFixtures.box(-1, -1, -1, 0, 0, 1);
        ConvexBrush right = MovementFixtures.box(0, -1, -1, 1, 0, 1);
        ConvexBrush wall = MovementFixtures.box(2, -1, -1, 3, 2, 1);
        CsTrace expected = trace(List.of(MovementFixtures.box(-1, -1, -1, 1, 0, 1), wall), -20, -1, 0, 120, -1, 0);
        // Wall face x = 80 units, expanded by the 12-unit half width: contact at x = 68 after 88 of 140 units.
        assertEquals((88 - ConvexBrushWorld.DIST_EPSILON) / 140, expected.fraction, 1e-12);
        assertEquals(-1, expected.normal.x, 0.0);
        assertTrue(expected.startSolid);
        for (List<ConvexBrush> split : List.of(List.of(left, right, wall), List.of(wall, right, left),
                List.of(right, wall, left))) {
            assertSameTrace("escape then wall", expected, trace(split, -20, -1, 0, 120, -1, 0));
        }
    }

    /** A separate solid across a gap narrower than the hull is a genuine entry even from an embedded start. */
    @Test
    public void embeddedStartStillHitsASolidAcrossAGapNarrowerThanTheHull() {
        ConvexBrush floor = MovementFixtures.box(-1, -1, -1, 0, 0, 1);
        ConvexBrush wall = MovementFixtures.box(0.5, -1, -1, 1.5, 2, 1);
        for (List<ConvexBrush> brushes : List.of(List.of(floor, wall), List.of(wall, floor))) {
            CsTrace trace = trace(brushes, -20, -1, 0, 40, -1, 0);
            // Wall face x = 20 units, expanded to x = 8 while the hull still overlaps the floor (expanded to 12).
            assertEquals((28 - ConvexBrushWorld.DIST_EPSILON) / 60, trace.fraction, 1e-12);
            assertEquals(-1, trace.normal.x, 0.0);
            assertTrue(trace.startSolid);
            assertFalse(trace.allSolid);
        }
    }

    /**
     * Invariant over seeded random obliquely clipped solids split into block cells, beside a separate wall across a
     * gap narrower than the hull: every sweep (embedded starts included) traces the split solid like the whole one.
     */
    @Test
    public void randomEmbeddedStartsEscapeSplitSolidsLikeTheWholeSolid() {
        Random random = new Random(37037037L);
        int escapes = 0;
        int escapeHits = 0;
        int hits = 0;
        for (int world = 0; world < 150; world++) {
            double ox = random.nextInt(9) - 4 + (random.nextBoolean() ? 100000 : 0), oy = random.nextInt(9) - 4;
            double oz = random.nextInt(9) - 4;
            AABB box = new AABB(ox, oy, oz, ox + 1 + random.nextInt(3), oy + 1 + random.nextInt(3),
                    oz + 1 + random.nextInt(3));
            List<BrushPlane> planes = new ArrayList<>(ConvexBrush.box(box).planes());
            double nx = random.nextGaussian(), ny = random.nextGaussian(), nz = random.nextGaussian();
            double px = box.minX + (0.3 + 0.4 * random.nextDouble()) * (box.maxX - box.minX);
            double py = box.minY + (0.3 + 0.4 * random.nextDouble()) * (box.maxY - box.minY);
            double pz = box.minZ + (0.3 + 0.4 * random.nextDouble()) * (box.maxZ - box.minZ);
            planes.add(BrushPlane.of(nx, ny, nz, nx * px + ny * py + nz * pz));
            double gap = 0.1 + 0.4 * random.nextDouble(); // 4..20 units, always below the 24-unit hull width
            ConvexBrush wall = MovementFixtures.box(box.maxX + gap, box.minY, box.minZ, box.maxX + gap + 1, box.maxY,
                    box.maxZ);
            List<ConvexBrush> whole = List.of(MovementFixtures.brush(planes, 0), wall);
            List<ConvexBrush> cells = new ArrayList<>(MovementFixtures.splitIntoCells(planes));
            cells.add(random.nextInt(cells.size() + 1), wall);
            ConvexBrushWorld expectedWorld = new ConvexBrushWorld(whole);
            ConvexBrushWorld splitWorld = new ConvexBrushWorld(cells);
            ConvexBrushWorld reversedWorld = new ConvexBrushWorld(new ArrayList<>(cells.reversed()));
            for (int sweep = 0; sweep < 40; sweep++) {
                double u = CsUnits.UNITS_PER_BLOCK;
                double sx = (box.minX - 0.3 + random.nextDouble() * (box.maxX - box.minX + 0.6)) * u;
                double sy = (box.minY + random.nextDouble() * (box.maxY - box.minY)) * u - HEIGHT * random.nextDouble();
                double sz = (box.minZ - 0.3 + random.nextDouble() * (box.maxZ - box.minZ + 0.6)) * u;
                double dx = random.nextGaussian() + (random.nextBoolean() ? 1.5 : 0), dy = random.nextGaussian();
                double dz = random.nextGaussian();
                double scale = (20 + random.nextDouble() * 180) / Math.sqrt(dx * dx + dy * dy + dz * dz);
                double ex = sx + dx * scale, ey = sy + dy * scale, ez = sz + dz * scale;
                CsTrace expected = new CsTrace();
                expectedWorld.traceHull(sx, sy, sz, ex, ey, ez, HALF_WIDTH, HEIGHT, expected);
                if (expected.allSolid) continue; // allSolid deliberately retains the original per-brush semantics.
                if (expected.startSolid) {
                    escapes++;
                    if (expected.hit()) escapeHits++;
                } else if (expected.hit()) {
                    hits++;
                }
                for (ConvexBrushWorld w : List.of(splitWorld, reversedWorld)) {
                    CsTrace actual = new CsTrace();
                    w.traceHull(sx, sy, sz, ex, ey, ez, HALF_WIDTH, HEIGHT, actual);
                    assertSameTrace("world " + world + " sweep " + sweep, expected, actual);
                }
            }
        }
        assertTrue("must exercise embedded escapes: " + escapes, escapes > 300);
        assertTrue("must exercise escapes into the wall: " + escapeHits, escapeHits > 30);
        assertTrue("must exercise clear-start hits: " + hits, hits > 300);
    }

    private static CsTrace trace(List<ConvexBrush> brushes, double sx, double sy, double sz, double ex, double ey,
            double ez) {
        CsTrace trace = new CsTrace();
        new ConvexBrushWorld(brushes).traceHull(sx, sy, sz, ex, ey, ez, HALF_WIDTH, HEIGHT, trace);
        return trace;
    }

    private static void assertSameTrace(String context, CsTrace expected, CsTrace actual) {
        assertEquals(context, expected.fraction, actual.fraction, 1e-8);
        assertEquals(context, expected.normal.x, actual.normal.x, 1e-9);
        assertEquals(context, expected.normal.y, actual.normal.y, 1e-9);
        assertEquals(context, expected.normal.z, actual.normal.z, 1e-9);
        assertEquals(context, expected.startSolid, actual.startSolid);
        assertEquals(context, expected.allSolid, actual.allSolid);
    }

    @Test
    public void boundaryScratchGrowsAndResetsWithTheBrushSet() {
        List<ConvexBrush> cells = new ArrayList<>();
        for (int x = -20; x < 20; x++) cells.add(MovementFixtures.box(x, -1, -1, x + 1, 0, 1));
        ConvexBrushWorld world = new ConvexBrushWorld(cells);
        CsTrace trace = new CsTrace();
        for (List<ConvexBrush> brushes : List.of(cells, List.of(cells.get(19)), cells)) {
            world.reset(brushes);
            world.traceHull(-14, 1, 0, -10, -1, 0, HALF_WIDTH, HEIGHT, trace);
            assertEquals(0.484375, trace.fraction, 1e-12);
            assertEquals(1, trace.normal.y, 1e-12);
        }
    }

    private static void assertUnionEquivalent(List<ConvexBrush> whole, List<ConvexBrush> split, double qx, double qy,
            double qz, double dx, double dy, double dz) {
        ConvexBrushWorld expectedWorld = new ConvexBrushWorld(whole);
        ConvexBrushWorld actualWorld = new ConvexBrushWorld(split);
        CsTrace expected = new CsTrace();
        CsTrace actual = new CsTrace();
        expectedWorld.traceHull(qx - dx, qy - dy, qz - dz, qx + dx, qy + dy, qz + dz,
                HALF_WIDTH, HEIGHT, expected);
        actualWorld.traceHull(qx - dx, qy - dy, qz - dz, qx + dx, qy + dy, qz + dz,
                HALF_WIDTH, HEIGHT, actual);
        String context = "approach (" + dx + "," + dy + "," + dz + ")";
        assertTrue(context, expected.hit());
        assertEquals(context, expected.fraction, actual.fraction, 1e-8);
        assertEquals(context, expected.normal.x, actual.normal.x, 1e-9);
        assertEquals(context, expected.normal.y, actual.normal.y, 1e-9);
        assertEquals(context, expected.normal.z, actual.normal.z, 1e-9);
        assertEquals(context, expected.startSolid, actual.startSolid);
        assertEquals(context, expected.allSolid, actual.allSolid);
        CsPlayerState e = new CsPlayerState();
        CsPlayerState a = new CsPlayerState();
        e.origin.set(qx - dx, qy - dy, qz - dz);
        a.origin.set(e.origin);
        // The first half gravity impulse is 800*DT/2; land at the same geometric contact as the trace.
        e.velocity.set(2 * dx / DT, 2 * dy / DT + 800 * DT / 2, 2 * dz / DT);
        a.velocity.set(e.velocity);
        CsGameMovement.step(e, MovementFixtures.input(0, 0, false, 0), CsMovementParams.COMPETITIVE, expectedWorld, DT);
        CsGameMovement.step(a, MovementFixtures.input(0, 0, false, 0), CsMovementParams.COMPETITIVE, actualWorld, DT);
        assertEquals(context, e.velocity.x, a.velocity.x, 1e-6);
        assertEquals(context, e.velocity.y, a.velocity.y, 1e-6);
        assertEquals(context, e.velocity.z, a.velocity.z, 1e-6);
        assertEquals(context, e.origin.x, a.origin.x, 1e-6);
        assertEquals(context, e.origin.y, a.origin.y, 1e-6);
        assertEquals(context, e.origin.z, a.origin.z, 1e-6);
    }

    @Test
    public void overLengthClippingNormalDoesNotSuppressTheAxialBevel() {
        // |n| = 1 + 3e-8: accepted by BrushPlane's unit tolerance, but its dot with +X is exactly 1.
        BrushPlane tilted = new BrushPlane(1, 1.0 / 4096, 0, 1);
        assertStationaryHullBesideCellIsClear(tilted);
    }

    @Test
    public void gridCanonicalisedNormalDoesNotSuppressTheAxialBevel() {
        // SurfRamps.canonicalPlane snaps each component independently to the 2^-24 grid (Math.rint(v / g) * g).
        BrushPlane exact = BrushPlane.of(1, 1.0 / 8192, 0, 1);
        double grid = 0x1.0p-24;
        BrushPlane canonical = new BrushPlane(Math.rint(exact.nx() / grid) * grid, Math.rint(exact.ny() / grid) * grid,
                Math.rint(exact.nz() / grid) * grid, Math.rint(exact.distance() / grid) * grid);
        assertEquals(1.0, canonical.nx(), 0.0); // the snap rounds the x component up to exactly 1
        assertTrue(canonical.ny() > 0.0);
        assertStationaryHullBesideCellIsClear(canonical);
    }

    private static void assertStationaryHullBesideCellIsClear(BrushPlane clip) {
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(clipped(clip, new AABB(0, 0, 0, 1, 1, 1))));
        CsTrace tr = new CsTrace();
        // Hull min x = 40.004 units, beyond the brush's max x of 40 units.
        world.traceHull(52.004, -36, 20, 52.004, -36, 20, HALF_WIDTH, HEIGHT, tr);
        assertFalse(tr.startSolid);
        assertFalse(tr.allSolid);
        assertEquals(1.0, tr.fraction, 0.0);
        // Still solid where the hull really overlaps.
        world.traceHull(51.996, -36, 20, 51.996, -36, 20, HALF_WIDTH, HEIGHT, tr);
        assertTrue(tr.startSolid);
    }
}
