package dev.csmc.grenade;

import java.util.ArrayList;
import java.util.List;
import dev.csmc.ballistics.mixin.GrenadeCollideInvoker;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Predicts a grenade's flight with exactly the per-tick step of {@link GrenadeProjectile}: the start-of-tick fluid
 * update (water state and current push), swept collision of the projectile's box, then
 * {@link GrenadePhysics#afterMove}. Entity glances are not predicted. Used by the client trajectory preview and the
 * server launch; shared here so tests can compare it with the server entity.
 */
public final class GrenadeArc {
    /** Upper bound on simulated ticks (the smoke/decoy rest guard). */
    public static final int MAX_TICKS = GrenadeTuning.REST_GUARD_TICKS;

    private GrenadeArc() {}

    /** Water state after the start-of-tick fluid update and the velocity including any current push. */
    public record Fluid(boolean inWater, Vec3 velocity) {}

    /** World queries for a projectile whose entity position (bottom centre of its box) is {@code position}. */
    public interface Collider {
        /** The part of {@code motion} the projectile can travel (per axis, like {@code Entity.move}). */
        Vec3 collide(Vec3 position, Vec3 motion);

        /** The entity's start-of-tick fluid update ({@code Entity.baseTick}). */
        Fluid fluid(Vec3 position, Vec3 velocity);
    }

    /** Reusable simulated path: entity positions with the start first. */
    public static final class Path {
        private final double[] xyz = new double[(MAX_TICKS + 1) * 3];
        private int size;
        private boolean landed;
        private boolean extinguished;

        public int size() {
            return size;
        }

        public double x(int i) {
            return xyz[i * 3];
        }

        public double y(int i) {
            return xyz[i * 3 + 1];
        }

        public double z(int i) {
            return xyz[i * 3 + 2];
        }

        /** Ended at rest or on a fire grenade's floor impact. */
        public boolean landed() {
            return landed;
        }

        /** A fire grenade that the server puts out in water at the last point. */
        public boolean extinguished() {
            return extinguished;
        }

        public Vec3 point(int i) {
            return new Vec3(x(i), y(i), z(i));
        }

        public Vec3 end() {
            return point(size - 1);
        }

        /** Allocating copy for tests and diagnostics. */
        public List<Vec3> points() {
            List<Vec3> points = new ArrayList<>(size);
            for (int i = 0; i < size; i++) points.add(point(i));
            return points;
        }

        private void add(Vec3 p) {
            xyz[size * 3] = p.x;
            xyz[size * 3 + 1] = p.y;
            xyz[size * 3 + 2] = p.z;
            size++;
        }
    }

    /**
     * World queries through an unspawned probe grenade, so collision (blocks, world border, collidable entities)
     * and the fluid update run the same vanilla code as a real grenade. Reuse one per level.
     */
    public static Collider collider(Level level) {
        GrenadeProjectile probe = new GrenadeProjectile(GrenadeRegistry.PROJECTILE, level);
        return new Collider() {
            @Override
            public Vec3 collide(Vec3 position, Vec3 motion) {
                probe.setPos(position);
                return ((GrenadeCollideInvoker) (Object) probe).csmc$collide(motion);
            }

            @Override
            public Fluid fluid(Vec3 position, Vec3 velocity) {
                return probe.predictFluid(position, velocity);
            }
        };
    }

    public static Path simulate(Collider collider, GrenadeKind kind, Vec3 start, Vec3 velocity) {
        Path path = new Path();
        simulate(collider, kind, start, velocity, path);
        return path;
    }

    /**
     * Simulates into {@code path} from entity position {@code start} with initial {@code velocity} until it
     * settles, a fire grenade lands or is put out, an HE/flash fuse runs out, or {@link #MAX_TICKS}.
     */
    public static void simulate(Collider collider, GrenadeKind kind, Vec3 start, Vec3 velocity, Path path) {
        int maxTicks = switch (kind) {
            case HE, FLASH -> GrenadeTuning.TIMED_FUSE_TICKS;
            case MOLOTOV, INCENDIARY -> GrenadeTuning.FIRE_FUSE_TICKS;
            default -> MAX_TICKS;
        };
        boolean fire = kind == GrenadeKind.MOLOTOV || kind == GrenadeKind.INCENDIARY;
        path.size = 0;
        path.landed = false;
        path.extinguished = false;
        Vec3 pos = start;
        Vec3 v = velocity;
        path.add(pos);
        for (int tick = 0; tick < maxTicks; tick++) {
            Fluid fluid = collider.fluid(pos, v);
            v = fluid.velocity();
            Vec3 collided = collider.collide(pos, v);
            boolean horizontal = !Mth.equal(v.x, collided.x) || !Mth.equal(v.z, collided.z);
            boolean vertical = v.y != collided.y;
            boolean onGround = vertical && v.y < 0.0;
            // Entity.move skips sub-threshold displacements but still reports the collision flags.
            Vec3 moved = collided.lengthSqr() > 1.0E-7 ? collided : Vec3.ZERO;
            pos = pos.add(moved);
            path.add(pos);
            GrenadePhysics.Step step = GrenadePhysics.afterMove(v, moved, horizontal, vertical, onGround,
                    fluid.inWater());
            // Server fuse order: water puts fire out before a floor impact counts.
            if (fire && fluid.inWater()) {
                path.extinguished = true;
                return;
            }
            if (step.settled() || fire && step.landedOnFloor()) {
                path.landed = true;
                return;
            }
            v = step.velocity();
        }
    }
}
