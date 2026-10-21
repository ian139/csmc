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
 * Near-active constraints (strictly inside an expanded plane by less than {@code CONTACT_TOLERANCE}) must never erase
 * a genuine entry: whenever the swept hull really enters a solid, the trace reports a hit before that point.
 */
public class NearActiveContactRegressionTest {
    private static final double DELTA = 2.7e-6;
    private static final double QX = 12 - DELTA / 3, QY = -DELTA / 3, QZ = 12 - DELTA / 3;

    /** Box [-0.5, 0]^3 blocks with its +++ corner clipped by a plane 2.7e-6 units deep. */
    private static List<ConvexBrush> microClippedCorner() {
        AABB bounds = new AABB(-0.5, -0.5, -0.5, 0, 0, 0);
        List<BrushPlane> planes = new ArrayList<>(ConvexBrush.box(bounds).planes());
        planes.add(BrushPlane.of(1, 1, 1, -DELTA / 40));
        return List.of(new ConvexBrush(planes, bounds));
    }

    @Test
    public void nearActiveAxialPlanesDoNotEraseTheClippedCornerEntry() {
        ConvexBrushWorld world = new ConvexBrushWorld(microClippedCorner());
        CsTrace trace = new CsTrace();
        world.traceHull(QX + 1, QY + 1, QZ + 1, QX - 50, QY - 50, QZ - 50, HALF_WIDTH, HEIGHT, trace);
        assertFalse(trace.startSolid);
        assertFalse(trace.allSolid);
        double sqrt3 = Math.sqrt(3);
        assertEquals((sqrt3 - ConvexBrushWorld.DIST_EPSILON) / (51 * sqrt3), trace.fraction, 1e-9);
        assertEquals(0.019254, trace.fraction, 1e-6);
        assertEquals(1 / sqrt3, trace.normal.x, 1e-9);
        assertEquals(1 / sqrt3, trace.normal.y, 1e-9);
        assertEquals(1 / sqrt3, trace.normal.z, 1e-9);
    }

    @Test
    public void competitiveIdleSubstepDoesNotTraverseTheClippedCorner() {
        List<ConvexBrush> brushes = microClippedCorner();
        CsPlayerState state = new CsPlayerState();
        state.origin.set(QX + 1, QY + 1, QZ + 1);
        state.velocity.set(-3060, -3053.3333333333335, -3060);
        CsGameMovement.step(state, MovementFixtures.input(0, 0, false, 0), CsMovementParams.COMPETITIVE,
                new ConvexBrushWorld(brushes), 1.0 / 60.0);
        // Still on the start side of the clipped facet and not overlapping the solid.
        double side = (state.origin.x - QX) + (state.origin.y - QY) + (state.origin.z - QZ);
        assertTrue("origin " + state.origin.x + "," + state.origin.y + "," + state.origin.z, side > 0);
        assertFalse(MovementFixtures.boxOverlaps(brushes.get(0).planes(), state.origin.x, state.origin.y,
                state.origin.z, HALF_WIDTH, HEIGHT, -1e-4));
    }

    /**
     * Invariant over seeded random convex brushes and unions (including corners clipped microscopically): a sweep
     * that starts clear and whose midpoint or endpoint hull is robustly inside a solid reports a hit before it.
     */
    @Test
    public void sweepsIntoASolidAlwaysHit() {
        Random random = new Random(20261020L);
        int checked = 0;
        for (int world = 0; world < 400; world++) {
            List<ConvexBrush> brushes = randomSolids(random);
            ConvexBrushWorld collision = new ConvexBrushWorld(brushes);
            ConvexBrushWorld reversed = new ConvexBrushWorld(new ArrayList<>(brushes.reversed()));
            ConvexBrush target = brushes.get(random.nextInt(brushes.size()));
            for (int sweep = 0; sweep < 24; sweep++) {
                double[] s = new double[3];
                double[] e = new double[3];
                if (!randomSweep(random, target, s, e) || overlapsAny(brushes, s, 1e-3)) continue;
                double[] mid = {(s[0] + e[0]) * 0.5, (s[1] + e[1]) * 0.5, (s[2] + e[2]) * 0.5};
                double limit = overlapsAny(brushes, mid, -1e-4) ? 0.5 : overlapsAny(brushes, e, -1e-4) ? 1.0 : -1;
                if (limit < 0) continue;
                checked++;
                for (ConvexBrushWorld w : List.of(collision, reversed)) {
                    CsTrace trace = new CsTrace();
                    w.traceHull(s[0], s[1], s[2], e[0], e[1], e[2], HALF_WIDTH, HEIGHT, trace);
                    String context = "world " + world + " sweep " + sweep + " fraction " + trace.fraction;
                    assertTrue(context, trace.hit());
                    assertTrue(context, trace.fraction < limit);
                    double n = trace.normal.x * trace.normal.x + trace.normal.y * trace.normal.y
                            + trace.normal.z * trace.normal.z;
                    assertEquals(context, 1.0, n, 1e-9);
                }
            }
        }
        assertTrue("must exercise penetrating sweeps: " + checked, checked > 2000);
    }

    private static boolean overlapsAny(List<ConvexBrush> brushes, double[] origin, double grow) {
        for (ConvexBrush brush : brushes) {
            if (MovementFixtures.boxOverlaps(brush.planes(), origin[0], origin[1], origin[2], HALF_WIDTH, HEIGHT,
                    grow)) {
                return true;
            }
        }
        return false;
    }

    /** One clipped brush, a union of clipped face-adjacent cells, or a clipped solid split into block cells. */
    private static List<ConvexBrush> randomSolids(Random random) {
        double ox = random.nextInt(9) - 4, oy = random.nextInt(9) - 4, oz = random.nextInt(9) - 4;
        switch (random.nextInt(3)) {
            case 0 -> {
                return List.of(clippedBox(random, ox, oy, oz, ox + 0.25 + random.nextDouble() * 1.5,
                        oy + 0.25 + random.nextDouble() * 1.5, oz + 0.25 + random.nextDouble() * 1.5));
            }
            case 1 -> {
                List<ConvexBrush> cells = new ArrayList<>();
                int count = 2 + random.nextInt(3);
                for (int i = 0; i < count; i++) {
                    int axis = random.nextInt(3);
                    double x = ox + (axis == 0 ? i : 0), y = oy + (axis == 1 ? i : 0), z = oz + (axis == 2 ? i : 0);
                    cells.add(clippedBox(random, x, y, z, x + 1, y + 1, z + 1));
                }
                return cells;
            }
            default -> {
                AABB box = new AABB(ox, oy, oz, ox + 1 + random.nextInt(2), oy + 1 + random.nextInt(2),
                        oz + 1 + random.nextInt(2));
                List<BrushPlane> planes = new ArrayList<>(ConvexBrush.box(box).planes());
                planes.add(obliqueClip(random, box));
                List<ConvexBrush> cells = new ArrayList<>();
                for (ConvexBrush cell : MovementFixtures.splitIntoCells(planes)) {
                    if (solid(cell.planes())) cells.add(cell);
                }
                return cells.isEmpty() ? List.of(ConvexBrush.box(box)) : cells;
            }
        }
    }

    private static ConvexBrush clippedBox(Random random, double minX, double minY, double minZ, double maxX,
            double maxY, double maxZ) {
        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        List<BrushPlane> planes = new ArrayList<>(ConvexBrush.box(box).planes());
        int clips = random.nextInt(3);
        for (int i = 0; i < clips; i++) {
            List<BrushPlane> candidate = new ArrayList<>(planes);
            candidate.add(random.nextBoolean() ? microCornerClip(random, box) : obliqueClip(random, box));
            if (solid(candidate)) planes = candidate;
        }
        return new ConvexBrush(planes, box);
    }

    /** A plane cutting a box corner between 1e-9 and 1e-4 units deep along a random outward diagonal. */
    private static BrushPlane microCornerClip(Random random, AABB box) {
        double sx = random.nextBoolean() ? 1 : -1, sy = random.nextBoolean() ? 1 : -1, sz = random.nextBoolean() ? 1 : -1;
        double nx = sx * (0.2 + random.nextDouble()), ny = sy * (0.2 + random.nextDouble());
        double nz = sz * (0.2 + random.nextDouble());
        double cx = sx > 0 ? box.maxX : box.minX, cy = sy > 0 ? box.maxY : box.minY, cz = sz > 0 ? box.maxZ : box.minZ;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double depthBlocks = Math.pow(10, -9 + 5 * random.nextDouble()) / CsUnits.UNITS_PER_BLOCK;
        return BrushPlane.of(nx, ny, nz, nx * cx + ny * cy + nz * cz - depthBlocks * length);
    }

    /** A random plane through a point inside the box's middle region. */
    private static BrushPlane obliqueClip(Random random, AABB box) {
        double nx = random.nextGaussian(), ny = random.nextGaussian(), nz = random.nextGaussian();
        double px = box.minX + (0.3 + 0.4 * random.nextDouble()) * (box.maxX - box.minX);
        double py = box.minY + (0.3 + 0.4 * random.nextDouble()) * (box.maxY - box.minY);
        double pz = box.minZ + (0.3 + 0.4 * random.nextDouble()) * (box.maxZ - box.minZ);
        return BrushPlane.of(nx, ny, nz, nx * px + ny * py + nz * pz);
    }

    /** At least 0.05 blocks of extent on every axis, so the brush is a robust solid for the brute-force check. */
    private static boolean solid(List<BrushPlane> planes) {
        List<double[]> vertices = MovementFixtures.vertices(planes);
        if (vertices.size() < 4) return false;
        for (int axis = 0; axis < 3; axis++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double[] v : vertices) {
                min = Math.min(min, v[axis]);
                max = Math.max(max, v[axis]);
            }
            if (max - min < 0.05) return false;
        }
        return true;
    }

    /**
     * A sweep aimed at the target: half through a random point of its bounds, half through one of its vertices
     * with the hull's opposite corner leading (the micro-clipped-corner configuration).
     */
    private static boolean randomSweep(Random random, ConvexBrush target, double[] s, double[] e) {
        double u = CsUnits.UNITS_PER_BLOCK;
        double[] aim = new double[3];
        double dx = random.nextGaussian(), dy = random.nextGaussian(), dz = random.nextGaussian();
        if (random.nextBoolean()) {
            List<double[]> vertices = MovementFixtures.vertices(target.planes());
            if (vertices.isEmpty()) return false;
            double[] v = vertices.get(random.nextInt(vertices.size()));
            AABB b = target.bounds();
            double sx = v[0] > (b.minX + b.maxX) * 0.5 ? 1 : -1;
            double sy = v[1] > (b.minY + b.maxY) * 0.5 ? 1 : -1;
            double sz = v[2] > (b.minZ + b.maxZ) * 0.5 ? 1 : -1;
            if (random.nextBoolean()) {
                // The bounds corner: inside a micro-clipped corner's cut, where the axial planes are near-active.
                v = new double[] {sx > 0 ? b.maxX : b.minX, sy > 0 ? b.maxY : b.minY, sz > 0 ? b.maxZ : b.minZ};
            }
            double tiny = Math.pow(10, -8 + 3 * random.nextDouble());
            aim[0] = v[0] * u + sx * HALF_WIDTH - sx * tiny;
            aim[1] = v[1] * u + (sy > 0 ? 0 : -HEIGHT) - sy * tiny;
            aim[2] = v[2] * u + sz * HALF_WIDTH - sz * tiny;
            dx = sx * (0.5 + random.nextDouble());
            dy = sy * (0.5 + random.nextDouble());
            dz = sz * (0.5 + random.nextDouble());
        } else {
            AABB b = target.bounds();
            aim[0] = (b.minX + random.nextDouble() * (b.maxX - b.minX)) * u;
            aim[1] = (b.minY + random.nextDouble() * (b.maxY - b.minY)) * u - HEIGHT * random.nextDouble();
            aim[2] = (b.minZ + random.nextDouble() * (b.maxZ - b.minZ)) * u;
        }
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double before = 1 + random.nextDouble() * 80, after = 1 + random.nextDouble() * 80;
        for (int axis = 0; axis < 3; axis++) {
            double d = (axis == 0 ? dx : axis == 1 ? dy : dz) / length;
            s[axis] = aim[axis] + d * before;
            e[axis] = aim[axis] - d * after;
        }
        return true;
    }
}
