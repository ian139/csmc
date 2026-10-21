package dev.csmc.grenade;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The one grenade flight step shared by the server entity ({@link GrenadeProjectile}) and the client trajectory
 * preview ({@link GrenadeArc}): given the velocity that was attempted this tick and the motion the block collision
 * actually allowed, compute the bounced, dragged and gravity-applied velocity for the next tick.
 */
public final class GrenadePhysics {
    private GrenadePhysics() {}

    /**
     * @param velocity next-tick velocity (zero once settled)
     * @param settled at rest on the ground
     * @param landedOnFloor hit a floor this tick (fire grenades detonate on it)
     * @param impact largest normal speed lost against a block this tick (bounce sound volume)
     */
    public record Step(Vec3 velocity, boolean settled, boolean landedOnFloor, double impact) {}

    /**
     * Mirrors {@code Entity.move} collision flags: {@code horizontalCollision} when x or z was clipped,
     * {@code verticalCollision} when y was clipped, {@code onGround} when it was clipped while falling.
     */
    public static Step afterMove(Vec3 v, Vec3 moved, boolean horizontalCollision, boolean verticalCollision,
            boolean onGround, boolean inWater) {
        boolean hitX = horizontalCollision && !Mth.equal(v.x, moved.x);
        boolean hitZ = horizontalCollision && !Mth.equal(v.z, moved.z);
        double nx = hitX ? -v.x * GrenadeTuning.RESTITUTION : v.x;
        double nz = hitZ ? -v.z * GrenadeTuning.RESTITUTION : v.z;
        double ny = v.y;
        double impact = Math.max(hitX ? Math.abs(v.x) : 0.0, hitZ ? Math.abs(v.z) : 0.0);
        boolean landedOnFloor = false;
        if (verticalCollision) {
            impact = Math.max(impact, Math.abs(v.y));
            if (v.y < 0.0) {
                landedOnFloor = true;
                ny = -v.y > GrenadeTuning.MIN_BOUNCE_SPEED ? -v.y * GrenadeTuning.RESTITUTION : 0.0;
                nx *= GrenadeTuning.FLOOR_BOUNCE_FRICTION;
                nz *= GrenadeTuning.FLOOR_BOUNCE_FRICTION;
            } else {
                ny = -v.y * GrenadeTuning.RESTITUTION;
            }
        }
        boolean grounded = onGround && ny <= 0.0;
        if (grounded) {
            nx *= GrenadeTuning.ROLL_FRICTION;
            nz *= GrenadeTuning.ROLL_FRICTION;
        }
        if (inWater) {
            nx *= GrenadeTuning.WATER_DRAG;
            ny = ny * GrenadeTuning.WATER_DRAG - GrenadeTuning.WATER_GRAVITY;
            nz *= GrenadeTuning.WATER_DRAG;
        } else {
            nx *= GrenadeTuning.AIR_DRAG;
            ny = ny * GrenadeTuning.AIR_DRAG - GrenadeTuning.GRAVITY;
            nz *= GrenadeTuning.AIR_DRAG;
        }
        if (grounded && nx * nx + nz * nz < GrenadeTuning.SETTLE_SPEED * GrenadeTuning.SETTLE_SPEED) {
            return new Step(Vec3.ZERO, true, landedOnFloor, impact);
        }
        return new Step(new Vec3(nx, ny, nz), false, landedOnFloor, impact);
    }
}
