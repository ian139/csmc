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
import org.junit.Test;

/**
 * Sweep results of {@link ConvexBrushWorld} in units: Quake/Source clip semantics (stop DIST_EPSILON short of the
 * surface, startSolid/allSolid) and exactness against a brute-force box ∩ brush overlap test, so rotated ramps and
 * sliver brushes give neither ghost hits nor tunnelling.
 */
public class ConvexBrushWorldTest {
    private static final double EPS = ConvexBrushWorld.DIST_EPSILON;

    @Test
    public void droppingOntoABlockStopsOneThirtySecondAboveItsTop() {
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.box(0, 0, 0, 1, 1, 1)));
        CsTrace tr = new CsTrace();
        world.traceHull(20, 100, 20, 20, -100, 20, HALF_WIDTH, HEIGHT, tr);
        assertTrue(tr.hit());
        assertEquals(40 + EPS, tr.end.y, 2 * ConvexBrushWorld.CONTACT_TOLERANCE);
        assertEquals((100 - 40 - EPS) / 200.0, tr.fraction, 1e-8);
        assertEquals(1.0, tr.normal.y, 1e-12);
        assertFalse(tr.startSolid);
    }

    @Test
    public void flushContactIsNotSolidAndSlidingAlongATopFaceIsFree() {
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.floor()));
        CsTrace tr = new CsTrace();
        // Feet exactly on the floor (Minecraft rests entities flush on block tops).
        world.traceHull(0, 0, 0, 300, 0, -200, HALF_WIDTH, HEIGHT, tr);
        assertFalse(tr.startSolid);
        assertFalse(tr.hit());
        assertEquals(0.0, tr.normal.length(), 0.0);
        assertEquals(300, tr.end.x, 0);
    }

    @Test
    public void startingInsideReportsStartSolidAndAllSolid() {
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.box(-1, -1, -1, 1, 1, 1)));
        CsTrace tr = new CsTrace();
        world.traceHull(0, -10, 0, 5, -10, 0, HALF_WIDTH, HEIGHT, tr);
        assertTrue(tr.startSolid);
        assertTrue(tr.allSolid);
        assertEquals(0.0, tr.fraction, 0);
        // Leaving the brush: startSolid but not allSolid.
        world.traceHull(0, -10, 0, 0, 200, 0, HALF_WIDTH, HEIGHT, tr);
        assertTrue(tr.startSolid);
        assertFalse(tr.allSolid);
    }

    @Test
    public void emptyAndFlatBrushesAreIgnored() {
        List<BrushPlane> empty = new ArrayList<>(MovementFixtures.box(0, 0, 0, 1, 1, 1).planes());
        empty.add(BrushPlane.of(0, 1, 0, -0.5)); // y <= -0.5 inside a cell at y >= 0: nothing
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(
                new ConvexBrush(empty, new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1)),
                MovementFixtures.box(5, 0, 0, 6, 1, 1)));
        assertEquals(1, world.brushCount());
        world.reset(List.of());
        assertEquals(0, world.brushCount());
        CsTrace tr = new CsTrace();
        world.traceHull(0, 0, 0, 1000, 0, 0, HALF_WIDTH, HEIGHT, tr);
        assertFalse(tr.hit());
    }

    /** A 60-degree ramp yawed 30 degrees; its slanted ends make edges that need edge x axis bevels. */
    private static List<BrushPlane> rotatedRamp() {
        MovementFixtures.Frame frame = new MovementFixtures.Frame(30, 2.3, 0.4, -1.7);
        return MovementFixtures.rampPlanes(frame, 60, 3, 0, 4);
    }

    /** A thin wedge: a block cell clipped by a yawed, steep plane leaving only a sliver near one corner edge. */
    private static List<BrushPlane> sliver() {
        List<BrushPlane> planes = new ArrayList<>(MovementFixtures.box(0, 0, 0, 1, 1, 1).planes());
        planes.add(BrushPlane.of(-0.8, 0.35, -0.45, -0.8 * 0.93 - 0.45 * 0.97 + 0.35 * 0.2));
        return planes;
    }

    @Test
    public void yawRotatedRampHasNoGhostHitsOrMisses() {
        assertExactAgainstBruteForce(rotatedRamp(), 1234L);
    }

    @Test
    public void sliverBrushHasNoGhostHitsOrMisses() {
        assertExactAgainstBruteForce(sliver(), 99L);
    }

    @Test
    public void rampSplitIntoCellsAtHugeCoordinatesIsExact() {
        MovementFixtures.Frame frame = new MovementFixtures.Frame(30, 100000.25, 64, -100000.5);
        List<BrushPlane> ramp = MovementFixtures.rampPlanes(frame, 60, 2, 0, 2);
        List<ConvexBrush> cells = MovementFixtures.splitIntoCells(ramp);
        assertTrue(cells.size() > 4);
        ConvexBrushWorld world = new ConvexBrushWorld(cells);
        assertExactAgainstBruteForce(List.of(ramp), world, 7L, 400);
        assertGlidesAreClear(ramp, world, 8L);
    }

    private static void assertExactAgainstBruteForce(List<BrushPlane> planes, long seed) {
        ConvexBrushWorld world = new ConvexBrushWorld(List.of(MovementFixtures.brush(planes, 0.01)));
        assertExactAgainstBruteForce(List.of(planes), world, seed, 1500);
        assertGlidesAreClear(planes, world, seed + 1);
    }

    /**
     * Sweeps parallel to each face, with the hull's nearest corner 0.035 to 0.3 units outside the face plane for the
     * whole sweep: the plane separates hull and solid, so any hit (typically at an edge or seam) is a ghost.
     */
    private static void assertGlidesAreClear(List<BrushPlane> planes, ConvexBrushWorld world, long seed) {
        Random random = new Random(seed);
        CsTrace tr = new CsTrace();
        double u = CsUnits.UNITS_PER_BLOCK;
        List<double[]> vertices = MovementFixtures.vertices(planes);
        int glides = 0;
        for (BrushPlane face : planes) {
            List<double[]> onFace = new ArrayList<>();
            for (double[] v : vertices) {
                if (Math.abs(face.signedDistance(v[0], v[1], v[2])) < 1e-6 * (1 + Math.abs(face.distance()))) {
                    onFace.add(v);
                }
            }
            if (onFace.size() < 3) continue;
            double nx = face.nx(), ny = face.ny(), nz = face.nz();
            double minCorner = -HALF_WIDTH * Math.abs(nx) - HALF_WIDTH * Math.abs(nz) + (ny < 0 ? ny * HEIGHT : 0);
            for (int n = 0; n < 200; n++) {
                double[] q = new double[3];
                double total = 0;
                for (double[] v : onFace) {
                    double w = random.nextDouble();
                    total += w;
                    for (int i = 0; i < 3; i++) q[i] += w * v[i];
                }
                double gap = 0.035 + random.nextDouble() * 0.265;
                double t = gap - minCorner;
                double ox = q[0] / total * u + nx * t, oy = q[1] / total * u + ny * t, oz = q[2] / total * u + nz * t;
                double dx = random.nextGaussian(), dy = random.nextGaussian(), dz = random.nextGaussian();
                double along = dx * nx + dy * ny + dz * nz;
                dx -= along * nx;
                dy -= along * ny;
                dz -= along * nz;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double reach = 150.0 / len;
                world.traceHull(ox - dx * reach, oy - dy * reach, oz - dz * reach, ox + dx * reach, oy + dy * reach,
                        oz + dz * reach, HALF_WIDTH, HEIGHT, tr);
                assertFalse("ghost hit gliding " + gap + " u above a face, normal " + tr.normal, tr.hit());
                assertFalse(tr.startSolid);
                glides++;
            }
        }
        assertTrue(glides >= 800);
    }

    /**
     * Random sweeps around the solid: a reported hit must be a real contact (the box nudged by 0.1 units overlaps),
     * and nothing before the hit (or on a clear sweep) may overlap (the box shrunk by 0.01 units stays clear).
     */
    private static void assertExactAgainstBruteForce(List<List<BrushPlane>> solids, ConvexBrushWorld world, long seed,
            int sweeps) {
        double[] c = centre(solids.get(0));
        Random random = new Random(seed);
        CsTrace tr = new CsTrace();
        int hits = 0;
        for (int n = 0; n < sweeps; n++) {
            double sx = c[0] + (random.nextDouble() - 0.5) * 240;
            double sy = c[1] + (random.nextDouble() - 0.5) * 240 - HEIGHT / 2;
            double sz = c[2] + (random.nextDouble() - 0.5) * 240;
            if (overlaps(solids, sx, sy, sz, 0.05)) continue;
            double ex = c[0] + (random.nextDouble() - 0.5) * 240;
            double ey = c[1] + (random.nextDouble() - 0.5) * 240 - HEIGHT / 2;
            double ez = c[2] + (random.nextDouble() - 0.5) * 240;
            world.traceHull(sx, sy, sz, ex, ey, ez, HALF_WIDTH, HEIGHT, tr);
            assertFalse("start outside must not be solid", tr.startSolid);
            if (tr.hit()) {
                hits++;
                assertTrue("ghost hit at fraction " + tr.fraction,
                        overlaps(solids, tr.end.x, tr.end.y, tr.end.z, 0.1));
            }
            double limit = tr.fraction;
            for (int k = 0; k <= 40; k++) {
                double f = limit * k / 40.0;
                double px = sx + (ex - sx) * f, py = sy + (ey - sy) * f, pz = sz + (ez - sz) * f;
                assertFalse("swept into the solid at fraction " + f, overlaps(solids, px, py, pz, -0.01));
            }
        }
        assertTrue("sweeps must exercise hits: " + hits, hits > 20);
    }

    private static boolean overlaps(List<List<BrushPlane>> solids, double x, double y, double z, double grow) {
        for (List<BrushPlane> solid : solids) {
            if (MovementFixtures.boxOverlaps(solid, x, y, z, HALF_WIDTH, HEIGHT, grow)) return true;
        }
        return false;
    }

    private static double[] centre(List<BrushPlane> planes) {
        List<double[]> vertices = MovementFixtures.vertices(planes);
        double[] c = new double[3];
        for (double[] v : vertices) {
            c[0] += v[0];
            c[1] += v[1];
            c[2] += v[2];
        }
        for (int i = 0; i < 3; i++) c[i] = c[i] / vertices.size() * CsUnits.UNITS_PER_BLOCK;
        return c;
    }
}
