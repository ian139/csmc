package dev.csmc.grenade;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

/**
 * The client preview ({@link GrenadeArc}) against an independent re-statement of the server projectile step
 * ({@code GrenadeProjectile.physics}: move the box, then bounce/drag/gravity) over a flat floor at y = 0.
 */
public class GrenadeArcTest {
    private static final double EPS = 1.0E-9;

    /** Floor at y = 0, open sky, no water: clips only the downward motion, like Entity.collideBoundingBox. */
    private static final GrenadeArc.Collider FLOOR = new GrenadeArc.Collider() {
        @Override
        public Vec3 collide(Vec3 position, Vec3 motion) {
            double dy = motion.y < 0.0 ? Math.max(motion.y, Math.min(0.0, -position.y)) : motion.y;
            return new Vec3(motion.x, dy, motion.z);
        }

        @Override
        public GrenadeArc.Fluid fluid(Vec3 position, Vec3 velocity) {
            return new GrenadeArc.Fluid(false, velocity);
        }
    };

    @Test
    public void crawlingLobSpawnsAboveTheFloor() {
        // Crawling eye height is 0.4: the lob's 0.75-block drop must stop on the floor, not inside it.
        for (GrenadeThrow.Strength strength : GrenadeThrow.Strength.values()) {
            GrenadeThrow.Launch launch = GrenadeThrow.launch(FLOOR, new Vec3(0.0, 0.4, 0.0), 0.0F, 0.0F, Vec3.ZERO,
                    strength.value);
            assertTrue(strength + " at " + launch.position(), launch.position().y >= -EPS);
        }
    }

    @Test
    public void previewMatchesServerProjectileStep() {
        Vec3 start = new Vec3(0.0, 1.5, 0.0);
        Vec3 launch = GrenadeThrow.velocity(20.0F, 0.0F, new Vec3(0.05, 0.0, 0.0), 1.0);
        List<Vec3> path = GrenadeArc.simulate(FLOOR, GrenadeKind.DECOY, start, launch).points();

        Vec3 pos = start;
        Vec3 v = launch;
        boolean bounced = false;
        for (int i = 1; i < path.size(); i++) {
            // Server entity tick, written out from GrenadeProjectile/GrenadeTuning without GrenadeArc.
            double dy = v.y < 0.0 ? Math.max(v.y, -pos.y) : v.y;
            boolean vertical = dy != v.y;
            Vec3 moved = new Vec3(v.x, dy, v.z);
            if (moved.lengthSqr() <= 1.0E-7) moved = Vec3.ZERO;
            pos = pos.add(moved);
            assertVec("tick " + i, pos, path.get(i));
            double nx = v.x, ny = v.y, nz = v.z;
            if (vertical) {
                bounced = true;
                ny = -v.y > GrenadeTuning.MIN_BOUNCE_SPEED ? -v.y * GrenadeTuning.RESTITUTION : 0.0;
                nx *= GrenadeTuning.FLOOR_BOUNCE_FRICTION;
                nz *= GrenadeTuning.FLOOR_BOUNCE_FRICTION;
            }
            boolean grounded = vertical && ny <= 0.0;
            if (grounded) {
                nx *= GrenadeTuning.ROLL_FRICTION;
                nz *= GrenadeTuning.ROLL_FRICTION;
            }
            nx *= GrenadeTuning.AIR_DRAG;
            ny = ny * GrenadeTuning.AIR_DRAG - GrenadeTuning.GRAVITY;
            nz *= GrenadeTuning.AIR_DRAG;
            v = new Vec3(nx, ny, nz);
        }
        assertTrue("path reaches the floor and bounces", bounced);
    }

    @Test
    public void fireGrenadePreviewEndsOnFloorImpact() {
        GrenadeArc.Path path = GrenadeArc.simulate(FLOOR, GrenadeKind.MOLOTOV, new Vec3(0.0, 1.5, 0.0),
                GrenadeThrow.velocity(0.0F, 0.0F, Vec3.ZERO, 1.0));
        assertTrue(path.landed());
        assertEquals(0.0, path.end().y, EPS);
    }

    @Test
    public void heFusePreviewStopsAtDetonation() {
        GrenadeArc.Path path = GrenadeArc.simulate(FLOOR, GrenadeKind.HE, new Vec3(0.0, 1.5, 0.0),
                GrenadeThrow.velocity(-45.0F, 0.0F, Vec3.ZERO, 1.0));
        assertEquals(GrenadeTuning.TIMED_FUSE_TICKS + 1, path.points().size());
    }

    private static void assertVec(String what, Vec3 expected, Vec3 actual) {
        assertEquals(what + " x", expected.x, actual.x, EPS);
        assertEquals(what + " y", expected.y, actual.y, EPS);
        assertEquals(what + " z", expected.z, actual.z, EPS);
    }
}
