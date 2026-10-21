package dev.csmc.client.grenade;

import dev.csmc.grenade.GrenadeCloud;
import dev.csmc.grenade.GrenadeKind;
import dev.csmc.grenade.GrenadeProjectile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Client-side registry of tracked grenade entities and their purely cosmetic state (smooth age, puff layout,
 * tumble). Everything is touched on the client thread only and dropped on unload, world change or disconnect.
 *
 * <p>Cloud lifetime comes from synced entity data: the last synced age is re-anchored to the level's game time, so
 * growth and fades advance smoothly between the server's throttled data updates and freeze while the level does.
 */
final class GrenadeEntities {
    static final int SMOKE_PUFFS = 64;
    static final int FIRE_FLAMES = 22;
    /** Ticks over which a smoke cloud thins out before its server-side discard. */
    private static final float SMOKE_FADE_OUT = 50;
    private static final float SMOKE_FADE_IN = 4;
    private static final float FIRE_FADE_OUT = 20;
    private static final float FIRE_FADE_IN = 5;
    /** Tumble radius of a grenade body in blocks: angle advanced per block travelled. */
    private static final float ROLL_RADIUS = 0.08f;
    private static final float MAX_SPIN_PER_TICK = 0.9f;

    static final List<GrenadeCloud> CLOUDS = new ArrayList<>();
    static final List<GrenadeProjectile> PROJECTILES = new ArrayList<>();
    private static final Map<Integer, CloudState> CLOUD_STATE = new HashMap<>();
    private static final Map<Integer, Tumble> TUMBLE = new HashMap<>();
    private static final Vector3f AXIS = new Vector3f();
    private static final Vector3f LONG_AXIS = new Vector3f();
    private static final Quaternionf STEP = new Quaternionf();

    private GrenadeEntities() {}

    static void load(Entity entity) {
        if (entity instanceof GrenadeCloud cloud && !CLOUDS.contains(cloud)) CLOUDS.add(cloud);
        else if (entity instanceof GrenadeProjectile projectile && !PROJECTILES.contains(projectile)) PROJECTILES.add(projectile);
    }

    static void unload(Entity entity) {
        if (entity instanceof GrenadeCloud cloud) {
            CLOUDS.remove(cloud);
            CLOUD_STATE.remove(cloud.getId());
        } else if (entity instanceof GrenadeProjectile projectile) {
            PROJECTILES.remove(projectile);
            TUMBLE.remove(projectile.getId());
        }
    }

    static void clear() {
        CLOUDS.clear();
        PROJECTILES.clear();
        CLOUD_STATE.clear();
        TUMBLE.clear();
    }

    /** Per client tick: drop stale entries, keep age anchors fresh and integrate projectile tumble. */
    static void tick(ClientLevel level) {
        for (int i = CLOUDS.size() - 1; i >= 0; i--) {
            GrenadeCloud cloud = CLOUDS.get(i);
            if (cloud.isRemoved() || cloud.level() != level) {
                CLOUDS.remove(i);
                CLOUD_STATE.remove(cloud.getId());
            } else {
                state(cloud).sync(cloud, level.getGameTime());
            }
        }
        for (int i = PROJECTILES.size() - 1; i >= 0; i--) {
            GrenadeProjectile projectile = PROJECTILES.get(i);
            if (projectile.isRemoved() || projectile.level() != level) {
                PROJECTILES.remove(i);
                TUMBLE.remove(projectile.getId());
            } else {
                tumble(projectile);
            }
        }
    }

    static CloudState state(GrenadeCloud cloud) {
        CloudState state = CLOUD_STATE.get(cloud.getId());
        if (state == null || state.cloud != cloud || state.kind != cloud.getKind()) {
            state = new CloudState(cloud);
            CLOUD_STATE.put(cloud.getId(), state);
        }
        return state;
    }

    static float height(GrenadeKind kind) {
        return kind == GrenadeKind.SMOKE ? GrenadeCloud.SMOKE_HEIGHT : GrenadeCloud.FIRE_HEIGHT;
    }

    static float maxRadius(GrenadeCloud cloud) {
        float authored = cloud.getKind() == GrenadeKind.SMOKE ? GrenadeCloud.SMOKE_RADIUS : GrenadeCloud.FIRE_RADIUS;
        float synced = cloud.getMaxRadius();
        // Synced data is authoritative but bounded: a corrupt value must not produce a world-sized volume.
        return Float.isFinite(synced) ? Math.max(authored, Math.min(synced, authored * 2)) : authored;
    }

    /** Smoothed client view of one cloud. */
    static final class CloudState {
        final GrenadeCloud cloud;
        final GrenadeKind kind;
        final long seed;
        /** Smoke puffs: radial fraction, angle, height fraction, half size, spin, drift; or fire flame layout. */
        final float[] rho;
        final float[] theta;
        final float[] lift;
        final float[] size;
        final float[] spin;
        final float[] drift;
        private int syncedAge = Integer.MIN_VALUE;
        private long anchorTime;
        /** Fire only: per-flame floor offset (blocks) and whether a burnable floor cell exists there. */
        float[] floor;
        boolean[] placed;

        CloudState(GrenadeCloud cloud) {
            this.cloud = cloud;
            this.kind = cloud.getKind();
            this.seed = cloud.getUUID().getLeastSignificantBits() ^ cloud.getUUID().getMostSignificantBits();
            int count = kind == GrenadeKind.SMOKE ? SMOKE_PUFFS : FIRE_FLAMES;
            rho = new float[count];
            theta = new float[count];
            lift = new float[count];
            size = new float[count];
            spin = new float[count];
            drift = new float[count];
            RandomSource random = RandomSource.create(seed);
            boolean smoke = kind == GrenadeKind.SMOKE;
            for (int i = 0; i < count; i++) {
                // A third of the puffs ride the rim, so the silhouette reaches the full radius densely.
                float radial = smoke && i % 3 == 0 ? 0.72f + 0.2f * random.nextFloat() : (float) Math.sqrt(random.nextFloat());
                rho[i] = radial * (smoke ? 0.85f : 0.92f);
                theta[i] = random.nextFloat() * Mth.TWO_PI;
                lift[i] = random.nextFloat();
                size[i] = smoke ? 1.35f + random.nextFloat() * 0.8f : 0.45f + random.nextFloat() * 0.25f;
                spin[i] = random.nextFloat() * Mth.TWO_PI;
                drift[i] = (random.nextFloat() - 0.5f) * (smoke ? 0.02f : 1f);
            }
        }

        void sync(GrenadeCloud cloud, long gameTime) {
            int age = cloud.getAgeTicks();
            if (age != syncedAge) {
                syncedAge = age;
                anchorTime = gameTime;
            }
        }

        /** Interpolated age in ticks, bounded by the synced duration. */
        float age(long gameTime, float partialTick) {
            if (syncedAge == Integer.MIN_VALUE) sync(cloud, gameTime);
            float age = syncedAge + Math.max(0, gameTime - anchorTime) + partialTick;
            return Mth.clamp(age, 0, Math.max(1, cloud.getDurationTicks()));
        }

        /** 0..1 smooth growth of a smoke cloud (fire pools spawn at full size). */
        float growth(float age) {
            if (cloud.getKind() != GrenadeKind.SMOKE) return 1;
            float t = Mth.clamp(age / Math.max(1, GrenadeCloud.SMOKE_GROW_TICKS), 0, 1);
            float eased = 1 - (1 - t) * (1 - t) * (1 - t);
            // Honour a larger synced radius (e.g. after a late join where age is already past growth).
            return Math.max(eased, Mth.clamp(cloud.getRadius() / maxRadius(cloud), 0, 1));
        }

        /** 0..1 visibility envelope from fade-in and fade-out. */
        float life(float age) {
            boolean smoke = cloud.getKind() == GrenadeKind.SMOKE;
            float remaining = Math.max(0, cloud.getDurationTicks() - age);
            float in = Mth.clamp(age / (smoke ? SMOKE_FADE_IN : FIRE_FADE_IN), 0, 1);
            float out = Mth.clamp(remaining / (smoke ? SMOKE_FADE_OUT : FIRE_FADE_OUT), 0, 1);
            return in * out;
        }

        float radius(float age) {
            return maxRadius(cloud) * growth(age);
        }

        /** Visual top of the smoke column above the cloud origin. */
        float top(float age) {
            return height(cloud.getKind()) * (0.35f + 0.65f * growth(age)) + 0.6f;
        }

        /**
         * 0..1 opacity density of smoke at a world point: a solid core, a soft rim and a soft top, matching the
         * rendered puff layout closely enough for fog, overlay and name-tag occlusion.
         */
        float density(double x, double y, double z, float age) {
            if (cloud.getKind() != GrenadeKind.SMOKE) return 0;
            float radius = radius(age);
            if (radius < 0.05f) return 0;
            double dy = y - cloud.getY();
            float top = top(age);
            if (dy < -0.5 || dy > top + 0.4) return 0;
            double dx = x - cloud.getX();
            double dz = z - cloud.getZ();
            float rho = (float) (Math.sqrt(dx * dx + dz * dz) / (radius + 0.6f));
            if (rho >= 1.1f) return 0;
            float horizontal = 1 - smoothstep(0.75f, 1.1f, rho);
            float vertical = (1 - smoothstep(top - 0.6f, top + 0.4f, (float) dy)) * smoothstep(-0.5f, 0.1f, (float) dy);
            return horizontal * vertical * life(age);
        }
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Highest smoke density at a point across all tracked clouds. */
    static float smokeAt(ClientLevel level, double x, double y, double z, float partialTick) {
        float best = 0;
        long time = level.getGameTime();
        for (GrenadeCloud cloud : CLOUDS) {
            if (cloud.getKind() != GrenadeKind.SMOKE || cloud.level() != level) continue;
            CloudState state = state(cloud);
            best = Math.max(best, state.density(x, y, z, state.age(time, partialTick)));
        }
        return best;
    }

    /**
     * Integrated smoke thickness (density × blocks) along a segment, stopping early once it exceeds {@code limit}.
     * Samples every half block, at most 96 samples.
     */
    static float smokeAlong(ClientLevel level, Vec3 from, Vec3 to, float partialTick, float limit) {
        if (CLOUDS.isEmpty()) return 0;
        double length = from.distanceTo(to);
        if (!(length > 1e-3)) return 0;
        long time = level.getGameTime();
        float total = 0;
        for (GrenadeCloud cloud : CLOUDS) {
            if (cloud.getKind() != GrenadeKind.SMOKE || cloud.level() != level) continue;
            CloudState state = state(cloud);
            float age = state.age(time, partialTick);
            double reach = state.radius(age) + 1.2;
            double top = cloud.getY() + state.top(age) + 0.5;
            // Segment-vs-cylinder bounding test before sampling.
            if (Math.max(from.y, to.y) < cloud.getY() - 0.6 || Math.min(from.y, to.y) > top) continue;
            if (distanceToSegmentXZ(cloud.getX(), cloud.getZ(), from, to) > reach) continue;
            int samples = (int) Math.min(96, Math.ceil(length / 0.5));
            double step = length / samples;
            for (int i = 0; i < samples; i++) {
                double t = (i + 0.5) / samples;
                total += state.density(Mth.lerp(t, from.x, to.x), Mth.lerp(t, from.y, to.y), Mth.lerp(t, from.z, to.z), age) * (float) step;
                if (total > limit) return total;
            }
        }
        return total;
    }

    private static double distanceToSegmentXZ(double px, double pz, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double lengthSqr = dx * dx + dz * dz;
        double t = lengthSqr < 1e-9 ? 0 : Mth.clamp(((px - a.x) * dx + (pz - a.z) * dz) / lengthSqr, 0, 1);
        double ex = a.x + dx * t - px;
        double ez = a.z + dz * t - pz;
        return Math.sqrt(ex * ex + ez * ez);
    }

    /** Previous/current orientation of a flying grenade, advanced from its observed motion each tick. */
    static final class Tumble {
        final Quaternionf previous = new Quaternionf();
        final Quaternionf current = new Quaternionf();
        Quaternionf rest;
        /** Position at the previous client tick; server updates arrive before the entity's old position is reset. */
        double lastX, lastY, lastZ;
    }

    static Tumble tumbleOf(GrenadeProjectile projectile) {
        return TUMBLE.get(projectile.getId());
    }

    private static void tumble(GrenadeProjectile projectile) {
        Tumble tumble = TUMBLE.get(projectile.getId());
        if (tumble == null) {
            tumble = new Tumble();
            // Deterministic initial attitude per entity, so remote viewers do not all see identical throws.
            RandomSource random = RandomSource.create(projectile.getUUID().getLeastSignificantBits());
            tumble.current.rotationXYZ(random.nextFloat() * Mth.TWO_PI, random.nextFloat() * Mth.TWO_PI, 0);
            tumble.previous.set(tumble.current);
            tumble.lastX = projectile.getX();
            tumble.lastY = projectile.getY();
            tumble.lastZ = projectile.getZ();
            TUMBLE.put(projectile.getId(), tumble);
        }
        tumble.previous.set(tumble.current);
        double dx = projectile.getX() - tumble.lastX;
        double dy = projectile.getY() - tumble.lastY;
        double dz = projectile.getZ() - tumble.lastZ;
        tumble.lastX = projectile.getX();
        tumble.lastY = projectile.getY();
        tumble.lastZ = projectile.getZ();
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (projectile.isSettled()) {
            if (tumble.rest == null) {
                // Lie the body on its side, keeping the heading its long axis had when it stopped.
                tumble.current.transform(LONG_AXIS.set(0, 1, 0));
                float hx = LONG_AXIS.x;
                float hz = LONG_AXIS.z;
                float horizontal = (float) Math.sqrt(hx * hx + hz * hz);
                if (horizontal < 1e-3f) {
                    hx = 0;
                    hz = 1;
                } else {
                    hx /= horizontal;
                    hz /= horizontal;
                }
                tumble.rest = new Quaternionf().rotationTo(0, 1, 0, hx, 0, hz);
            }
            tumble.current.slerp(tumble.rest, 0.35f);
            return;
        }
        tumble.rest = null;
        if (moved < 1e-3) return;
        // Roll about the axis perpendicular to travel and world up (pure falls spin about X).
        AXIS.set((float) dz, 0, (float) -dx);
        if (AXIS.lengthSquared() < 1e-8f) AXIS.set(1, 0, 0);
        AXIS.normalize();
        float angle = (float) Math.min(MAX_SPIN_PER_TICK, moved / ROLL_RADIUS * 0.25);
        tumble.current.premul(STEP.rotationAxis(angle, AXIS.x, AXIS.y, AXIS.z)).normalize();
    }
}
