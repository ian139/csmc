package dev.csmc.client.movement;

import dev.csmc.client.movement.mixin.EntityAccessor;
import dev.csmc.movement.CsMovement;
import dev.csmc.movement.MovementResolve;
import dev.csmc.movement.geometry.AnalyticCollision;
import dev.csmc.movement.physics.ConvexBrushWorld;
import dev.csmc.movement.physics.CsGameMovement;
import dev.csmc.movement.physics.CsMoveInput;
import dev.csmc.movement.physics.CsMovementParams;
import dev.csmc.movement.physics.CsPlayerState;
import dev.csmc.movement.physics.CsTrace;
import dev.csmc.movement.physics.CsUnits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side CS movement of the local player. While {@link #handles} is true it replaces vanilla travel: each
 * Minecraft tick runs {@link CsMovementParams#substepsPerTick()} {@link CsGameMovement#step} substeps (the
 * simulated server tick rate) and commits the result through vanilla {@code Entity.move}, so step sounds,
 * inside-block effects, stuck multipliers and the server's single {@code move(MoverType.PLAYER, …)} replay all see
 * an ordinary player move. Velocity lives in {@code deltaMovement} between ticks, so knockback, explosions and
 * entity pushing still apply. In hand-off states (fluids, ladders, flight, elytra, vehicles, spectators,
 * levitation) vanilla travel runs, but movement-geometry solids still block the player ({@link #clipHandOff}).
 *
 * <p>Approximations, each labeled where it is implemented: 40 units per block ({@link CsUnits}); the Minecraft
 * bounding box as the CS hull and {@code maxUpStep()} as the step height (CS uses 18 units); surface friction from
 * block slipperiness; Source's in-air duck/unduck origin shift; and the replay guard {@link #REPLAY_TOLERANCE}.
 */
public final class LocalMovement {
    /** {@code cl_forwardspeed} / {@code cl_sidespeed} default (Valve Developer Community cvar list), units/s. */
    public static final double MOVE_COMMAND = 450.0;
    private static final double TICK_SECONDS = 1.0 / CsUnits.TICKS_PER_SECOND;
    /** Default {@code Block.getFriction()} (slipperiness) of ordinary blocks. */
    private static final double DEFAULT_SLIPPERINESS = 0.6;
    /** Lowest surface friction handed to the simulation (blue ice), so ground acceleration never vanishes. */
    private static final double MIN_SURFACE_FRICTION = 0.05;
    /**
     * Largest horizontal difference, in blocks, tolerated between the CS move and Minecraft's axis-separated replay
     * of the same displacement ({@code Entity.collide}, which the server runs in {@code handleMovePlayer}). Beyond
     * it the client commits {@link MovementResolve#fallback} instead (the replay when it ends clear of movement
     * geometry, else the replay's part swept against the combined world, else no move), so the server never sees a
     * mismatch above its 0.25-block "moved wrongly" limit and the hull never ends inside an analytic solid.
     * Approximation: around outer corners the committed path may then differ from Source's swept slide.
     */
    private static final double REPLAY_TOLERANCE = 0.05;
    /** {@code LivingEntity.aiStep} zeroes delta-movement components below this many blocks per tick. */
    private static final double VANILLA_ZEROING = 0.003;

    private static final LocalMovement INSTANCE = new LocalMovement();

    @Nullable
    private static Entity committing;
    private static Vec3 committingMove = Vec3.ZERO;
    private static boolean committingGrounded;

    private final CsPlayerState state = new CsPlayerState();
    private final MovementWorld world = new MovementWorld();
    private final MovementWorld clipWorld = new MovementWorld();
    private final CsTrace trace = new CsTrace();
    private final TickReport report = new TickReport();
    @Nullable
    private LocalPlayer bound;
    private boolean handledThisTick;
    private boolean handledLastTick;
    private double committedX;
    private double committedY;
    private double committedZ;
    private double writtenX;
    private double writtenY;
    private double writtenZ;
    private float lastYaw;
    private boolean lastDucked;

    private LocalMovement() {}

    /** What the last client tick did, for the dev harness. */
    public static TickReport report() {
        return INSTANCE.report;
    }

    /** Whether CS movement replaces vanilla travel for {@code player} this tick. */
    public static boolean handles(LocalPlayer player) {
        return CsMovement.active(player) && !handOff(player);
    }

    /** States where vanilla movement takes over: fluids, ladders, flight, elytra, vehicles, spectators, levitation. */
    public static boolean handOff(LocalPlayer player) {
        return player.isSpectator() || player.noPhysics || player.getAbilities().flying || player.isFallFlying()
                || player.isPassenger() || player.isInWater() || player.isInLava() || player.isSwimming()
                || player.onClimbable() || player.hasEffect(MobEffects.LEVITATION) || player.isAutoSpinAttack()
                || player.isSleeping();
    }

    /** Called from {@code Player.travel}; returns true when CS movement handled the tick. */
    public static boolean travel(LocalPlayer player) {
        if (player != Minecraft.getInstance().player || !handles(player)) return false;
        INSTANCE.run(player);
        return true;
    }

    /** End of every client tick: a tick CS movement did not handle forces a full resync on the next one. */
    static void endClientTick() {
        INSTANCE.handledLastTick = INSTANCE.handledThisTick;
        INSTANCE.handledThisTick = false;
        if (!INSTANCE.handledLastTick) INSTANCE.report.mode = TickReport.Mode.VANILLA;
    }

    /** The committed move while CS movement is inside {@code Entity.move} for {@code entity}, else null. */
    @Nullable
    public static Vec3 committedMove(Entity entity) {
        return entity == committing ? committingMove : null;
    }

    /** CS ground state reported as {@code onGround} while committing for {@code entity}. */
    public static boolean committedGrounded() {
        return committingGrounded;
    }

    private void run(LocalPlayer player) {
        CsMovementParams params = CsMovement.params(player);
        handledThisTick = true;
        boolean resync = player != bound || !handledLastTick;
        bound = player;
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        // Teleports, server corrections and vanilla push-out move the player outside this simulation.
        if (x != committedX || y != committedY || z != committedZ) resync = true;
        Vec3 delta = player.getDeltaMovement();
        boolean external = resync || !matchesWritten(delta);
        state.origin.set(CsUnits.toUnits(x), CsUnits.toUnits(y), CsUnits.toUnits(z));
        if (external) {
            state.velocity.set(CsUnits.blocksPerTickToUnitsPerSecond(delta.x),
                    CsUnits.blocksPerTickToUnitsPerSecond(delta.y), CsUnits.blocksPerTickToUnitsPerSecond(delta.z));
        }
        // Sprinting would add vanilla's +30% MOVEMENT_SPEED modifier; CS has no sprint.
        if (player.isSprinting()) player.setSprinting(false);

        Input input = player.input;
        // Player.isImmobile(): dead or sleeping (sleeping is a hand-off state).
        boolean immobile = player.isDeadOrDying();
        boolean jump = !immobile && input.jumping;
        boolean ducked = player.getPose() == Pose.CROUCHING;
        if (resync) {
            lastYaw = player.getYRot();
            lastDucked = ducked;
            // A jump held while entering CS movement is not a fresh press.
            state.jumpHeldLastStep = jump;
        }
        AABB box = player.getBoundingBox();
        double halfWidth = CsUnits.toUnits(box.getXsize() * 0.5);
        double height = CsUnits.toUnits(box.getYsize());
        double stepHeight = CsUnits.toUnits(player.maxUpStep());
        double duckShift = CsUnits.toUnits(player.getDimensions(Pose.STANDING).height()
                - player.getDimensions(Pose.CROUCHING).height());
        double maxSpeed = maxSpeed(player);
        double reach = MovementResolve.collisionReach(state.velocity.length(), maxSpeed, params, stepHeight,
                duckShift);
        ConvexBrushWorld collision = world.collect(player.level(), player, box.inflate(reach), true);
        double depenetration = 0.0;
        if (resync && world.analyticSolids() > 0) {
            // Activation, teleports and respawns can leave the hull inside an analytic solid (players without CS
            // movement stand on its inscribed voxel approximation); exact tracing from there would stick.
            Vec3 push = MovementResolve.depenetrate(world.brushes(), world.vanillaSolids(), collision, box, trace);
            if (push != null && push != Vec3.ZERO) {
                x += push.x;
                y += push.y;
                z += push.z;
                player.setPos(x, y, z);
                box = player.getBoundingBox();
                state.origin.set(CsUnits.toUnits(x), CsUnits.toUnits(y), CsUnits.toUnits(z));
                depenetration = push.length();
            }
        }

        double duckMove = 0.0;
        if (!resync && ducked != lastDucked && !state.grounded) {
            duckMove = airDuck(collision, ducked, halfWidth, height, duckShift);
        }
        // The simulation only reads the ground surface while grounded (airborne friction is its own Source rule).
        double friction = surfaceFriction(player);
        CsMoveInput still = moveInput(0, 0, jump, ducked, false, player.getYRot(), maxSpeed, halfWidth, height,
                stepHeight, friction);
        if (external || duckMove != 0.0) {
            CsGameMovement.categorizePosition(state, still, collision);
        }

        double forward = 0.0;
        double side = 0.0;
        if (!immobile) {
            forward = ((input.up ? 1 : 0) - (input.down ? 1 : 0)) * MOVE_COMMAND;
            side = ((input.right ? 1 : 0) - (input.left ? 1 : 0)) * MOVE_COMMAND;
        }
        boolean walk = !immobile && CsMovementClient.walkKey().isDown();
        int substeps = params.substepsPerTick();
        double dt = TICK_SECONDS / substeps;
        float yaw = player.getYRot();
        double turn = Mth.wrapDegrees(yaw - lastYaw);
        boolean jumped = false;
        boolean landed = false;
        boolean surfed = false;
        for (int i = 1; i <= substeps; i++) {
            // Mouse turning between ticks is spread over the substeps like Source's per-usercmd view angles.
            CsMoveInput step = moveInput(forward, side, jump, ducked, walk, lastYaw + turn * i / substeps, maxSpeed,
                    halfWidth, height, stepHeight, friction);
            CsGameMovement.step(state, step, params, collision, dt);
            jumped |= state.jumpedThisStep;
            landed |= state.landedThisStep;
            surfed |= state.surfing;
        }
        lastYaw = yaw;
        lastDucked = ducked;

        Vec3 move = new Vec3(CsUnits.toBlocks(state.origin.x) - x, CsUnits.toBlocks(state.origin.y) - y,
                CsUnits.toBlocks(state.origin.z) - z);
        Vec3 stuck = ((EntityAccessor) player).csmc$stuckSpeedMultiplier();
        boolean stuckMove = stuck.lengthSqr() > 1.0E-7;
        if (stuckMove) {
            // Cobwebs, powder snow and berry bushes scale this tick's move and stop the player, as vanilla does.
            move = traceMove(collision, x, y, z, move.multiply(stuck), halfWidth, height);
            state.velocity.set(0.0, 0.0, 0.0);
        }
        Vec3 replay = ((EntityAccessor) player).csmc$collide(move);
        double errorX = replay.x - move.x;
        double errorZ = replay.z - move.z;
        boolean clamped = errorX * errorX + errorZ * errorZ > REPLAY_TOLERANCE * REPLAY_TOLERANCE;
        if (clamped) {
            // The replay ignores analytic solids: commit only an endpoint clear of the combined world that the
            // server's replay reproduces, and keep velocity only where that endpoint follows the CS move.
            MovementResolve.Fallback fallback = MovementResolve.fallback(collision, box, replay,
                    ((EntityAccessor) player)::csmc$collide, REPLAY_TOLERANCE, trace);
            Vec3 csMove = move;
            move = fallback.move();
            if (!Mth.equal(move.x, csMove.x)) state.velocity.x = 0.0;
            if (!Mth.equal(move.z, csMove.z)) state.velocity.z = 0.0;
            Vec3 normal = fallback.normal();
            if (normal == null && !Mth.equal(move.y, csMove.y)) state.velocity.y = 0.0;
            if (normal != null) {
                double into = state.velocity.x * normal.x + state.velocity.y * normal.y + state.velocity.z * normal.z;
                if (into < 0.0) {
                    state.velocity.set(state.velocity.x - normal.x * into, state.velocity.y - normal.y * into,
                            state.velocity.z - normal.z * into);
                }
            }
            state.origin.set(CsUnits.toUnits(x + move.x), CsUnits.toUnits(y + move.y), CsUnits.toUnits(z + move.z));
            CsGameMovement.categorizePosition(state, still, collision);
            surfed = state.surfing;
        }

        player.setDeltaMovement(CsUnits.unitsPerSecondToBlocksPerTick(state.velocity.x),
                CsUnits.unitsPerSecondToBlocksPerTick(state.velocity.y),
                CsUnits.unitsPerSecondToBlocksPerTick(state.velocity.z));
        committing = player;
        committingMove = move;
        committingGrounded = state.grounded;
        try {
            player.move(MoverType.SELF, move);
        } finally {
            committing = null;
            committingMove = Vec3.ZERO;
        }
        // Vanilla post-move effects (soul sand/honey speed factor, bubble columns, stuck reset) act on the velocity.
        Vec3 after = player.getDeltaMovement();
        writtenX = after.x;
        writtenY = after.y;
        writtenZ = after.z;
        state.velocity.set(CsUnits.blocksPerTickToUnitsPerSecond(after.x),
                CsUnits.blocksPerTickToUnitsPerSecond(after.y), CsUnits.blocksPerTickToUnitsPerSecond(after.z));
        committedX = player.getX();
        committedY = player.getY();
        committedZ = player.getZ();
        if (committedX != x + move.x || committedY != y + move.y || committedZ != z + move.z) {
            // Ground and surf state of the endpoint actually committed, not of the CS endpoint.
            state.origin.set(CsUnits.toUnits(committedX), CsUnits.toUnits(committedY), CsUnits.toUnits(committedZ));
            CsGameMovement.categorizePosition(state, still, collision);
            player.setDeltaMovement(CsUnits.unitsPerSecondToBlocksPerTick(state.velocity.x),
                    CsUnits.unitsPerSecondToBlocksPerTick(state.velocity.y),
                    CsUnits.unitsPerSecondToBlocksPerTick(state.velocity.z));
            Vec3 categorized = player.getDeltaMovement();
            writtenX = categorized.x;
            writtenY = categorized.y;
            writtenZ = categorized.z;
        }
        player.calculateEntityAnimation(false);

        report.mode = TickReport.Mode.CS;
        report.velocityX = state.velocity.x;
        report.velocityY = state.velocity.y;
        report.velocityZ = state.velocity.z;
        report.grounded = state.grounded;
        report.surfing = surfed;
        report.jumped = jumped;
        report.landed = landed;
        report.maxSpeed = maxSpeed;
        report.replayClamped = clamped;
        report.stuck = stuckMove;
        report.duckMove = duckMove;
        report.depenetration = depenetration;
        report.analyticSolids = world.analyticSolids();
    }

    /**
     * CS max ground speed: the held item's max speed from the server scaled by the {@code MOVEMENT_SPEED} attribute
     * relative to its base, so the scope slowdown modifier, Speed and Slowness apply (sprint is suppressed).
     */
    private static double maxSpeed(LocalPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        double scale = speed == null || speed.getBaseValue() <= 0.0 ? 1.0 : speed.getValue() / speed.getBaseValue();
        return CsMovement.clientHeldMaxSpeed() * scale;
    }

    /**
     * Approximation of Source's {@code m_surfaceFriction} (gamemovement.cpp {@code CategorizeGroundSurface}: the
     * ground surfaceprop friction ×1.25, capped at 1, so ordinary ground is 1.0). Minecraft blocks carry a
     * slipperiness instead (0.6 ordinary, 0.8 slime, 0.98 ice, 0.989 blue ice), mapped linearly so ordinary blocks
     * give 1.0 and ice about 0.05: {@code (1 - s) / (1 - 0.6)}, clamped to [0.05, 1].
     */
    private static double surfaceFriction(LocalPlayer player) {
        double slipperiness = player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement()).getBlock()
                .getFriction();
        return Mth.clamp((1.0 - slipperiness) / (1.0 - DEFAULT_SLIPPERINESS), MIN_SURFACE_FRICTION, 1.0);
    }

    /**
     * Source's in-air duck (gamemovement.cpp {@code FinishDuck}/{@code FinishUnDuck}): ducking in the air raises the
     * feet by the standing−crouching height difference so the head stays put, unducking lowers them again. Minecraft
     * shrinks and grows the box at the feet, so the origin is shifted here: up as far as the crouched hull fits
     * (always fully, since the space came from the standing box), down as far as the standing hull fits. Unlike
     * Source, an unduck without room below keeps the feet in place (Minecraft already chose to stand).
     * Returns the vertical shift in units.
     */
    private double airDuck(ConvexBrushWorld collision, boolean ducked, double halfWidth, double height,
            double duckShift) {
        double sx = state.origin.x;
        double sy = state.origin.y;
        double sz = state.origin.z;
        double ty = ducked ? sy + duckShift : sy - duckShift;
        collision.traceHull(sx, sy, sz, sx, ty, sz, halfWidth, height, trace);
        if (trace.startSolid) return 0.0;
        state.origin.y = trace.end.y;
        return trace.end.y - sy;
    }

    /** Hull sweep of {@code move} (blocks) from {@code (x, y, z)}; returns the unobstructed part in blocks. */
    private Vec3 traceMove(ConvexBrushWorld collision, double x, double y, double z, Vec3 move, double halfWidth,
            double height) {
        double sx = CsUnits.toUnits(x);
        double sy = CsUnits.toUnits(y);
        double sz = CsUnits.toUnits(z);
        collision.traceHull(sx, sy, sz, sx + CsUnits.toUnits(move.x), sy + CsUnits.toUnits(move.y),
                sz + CsUnits.toUnits(move.z), halfWidth, height, trace);
        if (trace.startSolid) return Vec3.ZERO;
        return new Vec3(CsUnits.toBlocks(trace.end.x - sx), CsUnits.toBlocks(trace.end.y - sy),
                CsUnits.toBlocks(trace.end.z - sz));
    }

    /** True when {@code delta} is what the last commit wrote, apart from {@code aiStep}'s small-value zeroing. */
    private boolean matchesWritten(Vec3 delta) {
        return matches(delta.x, writtenX) && matches(delta.y, writtenY) && matches(delta.z, writtenZ);
    }

    private static boolean matches(double now, double written) {
        return now == written || now == 0.0 && Math.abs(written) < VANILLA_ZEROING;
    }

    private static CsMoveInput moveInput(double forward, double side, boolean jump, boolean ducked, boolean walk,
            double yaw, double maxSpeed, double halfWidth, double height, double stepHeight, double friction) {
        return new CsMoveInput(forward, side, jump, ducked, walk, yaw, maxSpeed, halfWidth, height, stepHeight,
                friction);
    }

    /**
     * Hand-off movement for analytic players ({@link AnalyticCollision}): vanilla collision ignores movement
     * geometry for them, so {@code collided} (vanilla's result) is swept against those solids and slid along them
     * Source-style (up to four planes). A slide that would end inside a vanilla shape falls back to stopping at the
     * first contact. Starting inside a solid leaves vanilla's result untouched so the player can get out.
     */
    public static Vec3 clipHandOff(LocalPlayer player, Vec3 collided) {
        if (collided.lengthSqr() == 0.0) return collided;
        return INSTANCE.clip(player, collided);
    }

    private Vec3 clip(LocalPlayer player, Vec3 collided) {
        AABB box = player.getBoundingBox();
        ConvexBrushWorld collision = clipWorld.collect(player.level(), player, box.expandTowards(collided).inflate(0.1),
                false);
        if (clipWorld.analyticSolids() == 0) return collided;
        double halfWidth = CsUnits.toUnits(box.getXsize() * 0.5);
        double height = CsUnits.toUnits(box.getYsize());
        double sx = CsUnits.toUnits(player.getX());
        double sy = CsUnits.toUnits(box.minY);
        double sz = CsUnits.toUnits(player.getZ());
        double px = sx;
        double py = sy;
        double pz = sz;
        double rx = CsUnits.toUnits(collided.x);
        double ry = CsUnits.toUnits(collided.y);
        double rz = CsUnits.toUnits(collided.z);
        Vec3 firstContact = null;
        for (int plane = 0; plane < 4; plane++) {
            collision.traceHull(px, py, pz, px + rx, py + ry, pz + rz, halfWidth, height, trace);
            if (trace.startSolid) {
                if (plane == 0) return collided;
                break;
            }
            px = trace.end.x;
            py = trace.end.y;
            pz = trace.end.z;
            if (!trace.hit()) break;
            if (firstContact == null) {
                firstContact = new Vec3(CsUnits.toBlocks(px - sx), CsUnits.toBlocks(py - sy), CsUnits.toBlocks(pz - sz));
            }
            double left = 1.0 - trace.fraction;
            rx *= left;
            ry *= left;
            rz *= left;
            double into = rx * trace.normal.x + ry * trace.normal.y + rz * trace.normal.z;
            rx -= trace.normal.x * into;
            ry -= trace.normal.y * into;
            rz -= trace.normal.z * into;
            if (rx * rx + ry * ry + rz * rz < 1.0E-8) break;
        }
        Vec3 result = new Vec3(CsUnits.toBlocks(px - sx), CsUnits.toBlocks(py - sy), CsUnits.toBlocks(pz - sz));
        if (firstContact != null && !player.level().noCollision(player, box.move(result))) return firstContact;
        return result;
    }

    /** Per-tick telemetry of the local player's movement. */
    public static final class TickReport {
        public enum Mode { CS, VANILLA }

        public Mode mode = Mode.VANILLA;
        public double velocityX;
        public double velocityY;
        public double velocityZ;
        public boolean grounded;
        public boolean surfing;
        public boolean jumped;
        public boolean landed;
        public double maxSpeed;
        public boolean replayClamped;
        public boolean stuck;
        public double duckMove;
        /** Blocks the hull was pushed out of analytic solids on (re)activation this tick. */
        public double depenetration;
        public int analyticSolids;
    }
}
