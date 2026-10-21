package dev.csmc.movement.physics;

/**
 * Source-engine player movement ({@code CGameMovement::FullWalkMove} and the functions it calls), ported from Valve's
 * <a href="https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/game/shared/gamemovement.cpp#L2025-L2141">Source
 * SDK 2013 game/shared/gamemovement.cpp</a> (all function line ranges below refer to that revision; see also
 * {@code docs/dev/movement-reference.md}) with the Counter-Strike: Global Offensive server variables of {@link CsMovementParams}.
 *
 * <p>Axes: Source units on Minecraft axes (x east, y up, z south), so Source's {@code z}/{@code [2]} component is
 * {@code y} here. Yaw follows Minecraft: forward at yaw 0 is +z and at yaw 90 is -x; the right vector at yaw 0 is -x.
 *
 * <p>Deliberate differences, each labelled in the docs:
 * <ul>
 *   <li>Water, ladders, noclip, base (conveyor) velocity, duck transitions and fall damage are out of scope; ducking
 *       is a pose given by {@link CsMoveInput#ducked()} with its hull already in {@link CsMoveInput#height()}.</li>
 *   <li>The {@code sv_enablebunnyhopping 0} pre-jump cap is CS:GO behaviour that is not in the public SDK: it is
 *       implemented as TF2's public {@code CTFGameMovement::PreventBunnyJumping} (whole velocity scaled down to the cap
 *       before the impulse) with CS:GO's factor 1.1 of the 260 u/s base run speed (community analysis).</li>
 *   <li>Stamina ({@code sv_stamina*}) is a project approximation informed by community CS 1.6 code, not a verified
 *       CS:GO implementation; see {@link #staminaSpeedRatio}.</li>
 *   <li>The airborne surface friction of 0.25 that {@code CategorizePosition} sets at the end of a step (only while
 *       rising at {@code 0 < vy <= NON_JUMP_VELOCITY} with no ground found) is re-derived at the start of the next
 *       step from the velocity before {@code FinishGravity}; see {@link #airborneSurfaceFriction}.</li>
 * </ul>
 *
 * <p>No heap allocation per step: scratch vectors and traces live in a per-thread {@link Scratch}.
 */
public final class CsGameMovement {
    /** {@code CategorizePosition}: moving up faster than this is never on ground (gamemovement.cpp 3810). */
    public static final double NON_JUMP_VELOCITY = 140.0;
    /** Minimum plane normal y that can be stood on (gamemovement.cpp 3844/3849; CS:GO sv_standable_normal 0.7). */
    public static final double WALKABLE_NORMAL = 0.7;
    /** {@code TryPlayerMove} bump count (gamemovement.cpp 2575). */
    public static final int MAX_BUMPS = 4;
    /** {@code MAX_CLIP_PLANES} (gamemovement.cpp 26). */
    public static final int MAX_CLIP_PLANES = 5;
    /** {@code CategorizePosition} ground probe distance in units (gamemovement.cpp 3798). */
    public static final double GROUND_PROBE = 2.0;
    /** {@code COORD_RESOLUTION} = 1/32 unit
     * (<a href="https://github.com/ValveSoftware/source-sdk-2013/blob/0d8dceea4310fde5706b3ce1c70609d72a38efdf/mp/src/public/coordsize.h#L17-L19">coordsize.h</a>),
     * used by {@code StayOnGround}. */
    public static final double COORD_RESOLUTION = 1.0 / 32.0;
    /**
     * CS base run speed (u/s) used for the bunnyhop cap. Community CS2 analysis, extrapolated here to CS:GO:
     * <a href="https://github.com/zer0k-z/cs2-movement-issue-bhop/blob/58e80d1f462dcf31d14ca7b02847e72b3cb421bc/README.md#L53-L57">zer0k</a>
     * reports 1.1 x 260 = 286 u/s regardless of the weapon held. The whole-vector cap structure comes from
     * <a href="https://github.com/ValveSoftware/source-sdk-2013/blob/39f6dde8fbc238727c020d13b05ecadd31bda4c0/src/game/shared/tf/tf_gamemovement.cpp#L1086-L1112">TF2</a>
     * (which uses 1.2, not this CS factor).
     */
    public static final double BUNNYHOP_BASE_SPEED = 260.0;
    /** CS:GO {@code sv_enablebunnyhopping} help: "air-velocity clamping to 110% of maximum running speed". */
    public static final double BUNNYJUMP_MAX_SPEED_FACTOR = 1.1;
    /**
     * Surface friction while airborne and rising no faster than {@link #NON_JUMP_VELOCITY} (gamemovement.cpp
     * 3853-3857: set only in the failed-ground-probe branch, which a faster rise skips at 3810-3814).
     */
    public static final double RISING_AIR_FRICTION = 0.25;
    /** Project stamina percentage base, inspired by the CS 1.6 ratio cited in {@link #staminaSpeedRatio}. */
    public static final double STAMINA_RANGE = 100.0;
    /**
     * Project-selected 64 moves/s reference (matching the nominal competitive tick rate). Other step lengths use
     * {@code ratio^(dt * 64)}, inspired by the optional frame-time normalisation in
     * <a href="https://github.com/rehlds/ReGameDLL_CS/blob/b0889847fe6d03898be88acc9e366660efb40ab5/regamedll/pm_shared/pm_shared.cpp#L1059-L1078">ReGameDLL_CS</a>.
     * Its stamina variable, ratio formula and default reference setting differ; this is not a CS:GO source port.
     */
    public static final double STAMINA_REFERENCE_RATE = 64.0;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private CsGameMovement() {}

    /** Per-thread working storage, so steps on the server and client threads never share or allocate. */
    private static final class Scratch {
        final CsTrace trace = new CsTrace();
        final CsTrace trace2 = new CsTrace();
        final double[] planeX = new double[MAX_CLIP_PLANES];
        final double[] planeY = new double[MAX_CLIP_PLANES];
        final double[] planeZ = new double[MAX_CLIP_PLANES];
        final CsVec clip = new CsVec();
        CsPlayerState state;
        CsMoveInput input;
        CsMovementParams params;
        CsCollisionWorld world;
        double dt;
        double surfaceFriction;
    }

    /**
     * One Source {@code FullWalkMove} (not in water) of length {@code dt} seconds: CheckVelocity, StartGravity,
     * CheckJumpButton, ground Friction, WalkMove or AirMove, CategorizePosition, FinishGravity, plus CS:GO stamina.
     * Updates {@code state.origin}, {@code state.velocity} and every flag of {@link CsPlayerState}.
     */
    public static void step(CsPlayerState state, CsMoveInput input, CsMovementParams params, CsCollisionWorld world,
            double dt) {
        Scratch s = SCRATCH.get();
        s.state = state;
        s.input = input;
        s.params = params;
        s.world = world;
        s.dt = dt;
        CsVec v = state.velocity;

        boolean wasGrounded = state.grounded;
        state.jumpedThisStep = false;
        state.landedThisStep = false;
        state.surfing = false;

        // CGameMovement::PlayerMove: fall velocity is stored before moving; ReduceTimers recovers stamina.
        double fallVelocity = wasGrounded ? 0.0 : -v.y;
        if (state.stamina > 0.0) {
            state.stamina = Math.max(0.0, state.stamina - params.staminaRecoveryRate() * dt);
        }
        state.stamina = Math.min(state.stamina, params.staminaMax());

        // m_surfaceFriction as left by the previous CategorizePosition: the ground surface, or the airborne value for
        // the velocity that categorisation saw (before FinishGravity, i.e. half a gravity step higher than now).
        if (wasGrounded) {
            s.surfaceFriction = input.surfaceFriction();
        } else {
            s.surfaceFriction = airborneSurfaceFriction(v.y + params.gravity() * 0.5 * dt);
        }

        checkVelocity(v, params);
        startGravity(s);

        if (input.jump()) {
            checkJumpButton(s);
        }

        if (state.grounded) {
            v.y = 0.0;
            friction(s);
        }
        checkVelocity(v, params);

        if (state.grounded) {
            walkMove(s);
        } else {
            airMove(s);
        }

        categorize(s);
        checkVelocity(v, params);
        finishGravity(s);
        if (state.grounded) {
            v.y = 0.0;
        }

        if (state.grounded && !wasGrounded && !state.jumpedThisStep) {
            state.landedThisStep = true;
            // Project stamina approximation: landing adds sv_staminalandcost x pre-step fall speed.
            if (fallVelocity > 0.0) {
                state.stamina = Math.min(params.staminaMax(), state.stamina + params.staminaLandCost() * fallVelocity);
            }
        }
        state.jumpHeldLastStep = input.jump();
        s.state = null;
        s.input = null;
        s.params = null;
        s.world = null;
    }

    /**
     * Source {@code CategorizePosition} without moving: sets {@code grounded}/{@code groundNormalY} (zeroing vertical
     * velocity on ground like {@code SetGroundEntity}) and {@code surfing} when the probe finds only a non-walkable
     * slope. Use after teleports, mode entry or externally applied velocity.
     */
    public static void categorizePosition(CsPlayerState state, CsMoveInput input, CsCollisionWorld world) {
        Scratch s = SCRATCH.get();
        s.state = state;
        s.input = input;
        s.world = world;
        state.surfing = false;
        categorize(s);
        if (state.grounded) {
            state.velocity.y = 0.0;
        }
        s.state = null;
        s.input = null;
        s.world = null;
    }

    /**
     * Airborne {@code m_surfaceFriction} after {@code CategorizePosition} (Source SDK 2013 gamemovement.cpp
     * 3756-3865): rising faster than {@link #NON_JUMP_VELOCITY} skips the ground probe and leaves friction at 1;
     * otherwise a failed probe sets {@link #RISING_AIR_FRICTION} only when {@code vy > 0}. Project simplification:
     * the friction retained above 140 u/s is 1 rather than the last ground surface's value.
     */
    static double airborneSurfaceFriction(double categorizationVelocityY) {
        return categorizationVelocityY > 0.0 && categorizationVelocityY <= NON_JUMP_VELOCITY
                ? RISING_AIR_FRICTION : 1.0;
    }

    /**
     * Project stamina approximation: a percentage penalty in {@code [0, sv_staminamax]}; horizontal ground speed and
     * jump impulse scale by {@code (100 - stamina) / 100}. Inspired by the percentage-based ground multiplier in
     * <a href="https://github.com/rehlds/ReGameDLL_CS/blob/b0889847fe6d03898be88acc9e366660efb40ab5/regamedll/pm_shared/pm_shared.cpp#L1059-L1078">ReGameDLL_CS</a>,
     * whose actual CS 1.6 stamina variable and formula are different. The cost equations here are project choices.
     */
    public static double staminaSpeedRatio(double stamina) {
        return stamina > 0.0 ? (STAMINA_RANGE - stamina) / STAMINA_RANGE : 1.0;
    }

    /**
     * Run speed cap after the approximate CS:GO stance modifier: ducked x0.34, otherwise walking x0.52.
     * These are community-documented approximations (no official Valve primary source); see {@link CsMovementParams}.
     * Ducking takes precedence; the modifiers do not stack.
     */
    public static double effectiveMaxSpeed(CsMoveInput input, CsMovementParams params) {
        double max = input.maxSpeed();
        if (input.ducked()) return max * params.duckSpeedModifier();
        if (input.walk()) return max * params.walkSpeedModifier();
        return max;
    }

    /** {@code CheckVelocity} (gamemovement.cpp 3049-3087): NaN to zero, clamp each axis to sv_maxvelocity. */
    private static void checkVelocity(CsVec v, CsMovementParams params) {
        double max = params.maxVelocity();
        v.x = clampAxis(v.x, max);
        v.y = clampAxis(v.y, max);
        v.z = clampAxis(v.z, max);
    }

    private static double clampAxis(double value, double max) {
        if (Double.isNaN(value)) return 0.0;
        if (value > max) return max;
        if (value < -max) return -max;
        return value;
    }

    /** {@code StartGravity} (1248-1267): half the gravity of this step before moving. */
    private static void startGravity(Scratch s) {
        s.state.velocity.y -= s.params.gravity() * 0.5 * s.dt;
        checkVelocity(s.state.velocity, s.params);
    }

    /** {@code FinishGravity} (1684-1700): the other half after moving. */
    private static void finishGravity(Scratch s) {
        s.state.velocity.y -= s.params.gravity() * 0.5 * s.dt;
        checkVelocity(s.state.velocity, s.params);
    }

    /** {@code CheckJumpButton} (2354-2530) with CS:GO auto/enable bunnyhopping and stamina. */
    private static void checkJumpButton(Scratch s) {
        CsPlayerState state = s.state;
        CsMovementParams params = s.params;
        if (!state.grounded) return; // in air: no effect (the held bit is recorded at the end of step)
        if (state.jumpHeldLastStep && !params.autoBunnyhopping()) return; // don't pogo stick
        CsVec v = state.velocity;

        if (!params.enableBunnyhopping()) {
            // TF2 CTFGameMovement::PreventBunnyJumping structure with CS:GO's 110% of the base run speed.
            double cap = BUNNYJUMP_MAX_SPEED_FACTOR * BUNNYHOP_BASE_SPEED;
            double speed = v.length();
            if (speed > cap) {
                double fraction = cap / speed;
                v.x *= fraction;
                v.y *= fraction;
                v.z *= fraction;
            }
        }

        state.grounded = false;
        double impulse = params.jumpImpulse() * staminaSpeedRatio(state.stamina);
        if (s.input.ducked()) {
            v.y = impulse;
        } else {
            v.y += impulse;
        }
        // Approximation: jumping adds sv_staminajumpcost x the impulse (recovers before landing at CS:GO defaults).
        state.stamina = Math.min(params.staminaMax(), state.stamina + params.staminaJumpCost() * params.jumpImpulse());
        finishGravity(s);
        state.jumpedThisStep = true;
    }

    /** {@code Friction} (1612-1679) on ground: control = max(speed, sv_stopspeed). */
    private static void friction(Scratch s) {
        CsVec v = s.state.velocity;
        double speed = v.length();
        if (speed < 0.1) return;
        double friction = s.params.friction() * s.surfaceFriction;
        double control = speed < s.params.stopSpeed() ? s.params.stopSpeed() : speed;
        double drop = control * friction * s.dt;
        double newSpeed = Math.max(0.0, speed - drop);
        if (newSpeed != speed) {
            double scale = newSpeed / speed;
            v.x *= scale;
            v.y *= scale;
            v.z *= scale;
        }
    }

    /** Horizontal wish velocity from the input into {@code s.clip}; returns its length. */
    private static double wishVelocity(Scratch s) {
        double yaw = Math.toRadians(s.input.yawDegrees());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // forward (-sin, 0, cos); right (-cos, 0, -sin)
        double fmove = s.input.forwardMove();
        double smove = s.input.sideMove();
        double wx = -sin * fmove - cos * smove;
        double wz = cos * fmove - sin * smove;
        s.clip.set(wx, 0.0, wz);
        return Math.sqrt(wx * wx + wz * wz);
    }

    /** {@code WalkMove} (1895-2020). */
    private static void walkMove(Scratch s) {
        CsPlayerState state = s.state;
        CsVec v = state.velocity;
        double wishSpeed = wishVelocity(s);
        double wishX = 0.0, wishZ = 0.0;
        if (wishSpeed > 0.0) {
            wishX = s.clip.x / wishSpeed;
            wishZ = s.clip.z / wishSpeed;
        }
        double maxSpeed = effectiveMaxSpeed(s.input, s.params);
        if (wishSpeed != 0.0 && wishSpeed > maxSpeed) {
            wishSpeed = maxSpeed;
        }

        v.y = 0.0;
        accelerate(s, wishX, wishZ, wishSpeed, s.params.accelerate());
        v.y = 0.0;

        // Approximation: CS:GO stamina scales horizontal ground speed every ground move (CS 1.6 PM_WalkMove
        // structure), normalised to 64 moves per second.
        double ratio = staminaSpeedRatio(state.stamina);
        if (ratio < 1.0) {
            double scale = Math.pow(ratio, s.dt * STAMINA_REFERENCE_RATE);
            v.x *= scale;
            v.z *= scale;
        }

        double speed = v.length();
        if (speed < 1.0) {
            v.set(0.0, 0.0, 0.0);
            return;
        }

        CsVec origin = state.origin;
        double destX = origin.x + v.x * s.dt;
        double destY = origin.y;
        double destZ = origin.z + v.z * s.dt;
        CsTrace pm = s.trace;
        trace(s, origin.x, origin.y, origin.z, destX, destY, destZ, pm);
        if (pm.fraction == 1.0) {
            origin.set(pm.end);
            stayOnGround(s);
            return;
        }
        stepMove(s, destX, destY, destZ);
        stayOnGround(s);
    }

    /** {@code Accelerate} (1822-1854). */
    private static void accelerate(Scratch s, double wishX, double wishZ, double wishSpeed, double accel) {
        CsVec v = s.state.velocity;
        double currentSpeed = v.x * wishX + v.z * wishZ;
        double addSpeed = wishSpeed - currentSpeed;
        if (addSpeed <= 0.0) return;
        double accelSpeed = accel * s.dt * wishSpeed * s.surfaceFriction;
        if (accelSpeed > addSpeed) accelSpeed = addSpeed;
        v.x += accelSpeed * wishX;
        v.z += accelSpeed * wishZ;
    }

    /** {@code AirMove} (1753-1799). */
    private static void airMove(Scratch s) {
        double wishSpeed = wishVelocity(s);
        double wishX = 0.0, wishZ = 0.0;
        if (wishSpeed > 0.0) {
            wishX = s.clip.x / wishSpeed;
            wishZ = s.clip.z / wishSpeed;
        }
        double maxSpeed = effectiveMaxSpeed(s.input, s.params);
        if (wishSpeed != 0.0 && wishSpeed > maxSpeed) {
            wishSpeed = maxSpeed;
        }
        airAccelerate(s, wishX, wishZ, wishSpeed, s.params.airAccelerate());
        tryPlayerMove(s);
    }

    /** {@code AirAccelerate} (1707-1748): the gain is capped by sv_air_max_wishspeed, the rate is not. */
    private static void airAccelerate(Scratch s, double wishX, double wishZ, double wishSpeed, double accel) {
        CsVec v = s.state.velocity;
        double wishSpd = Math.min(wishSpeed, s.params.airMaxWishSpeed());
        double currentSpeed = v.x * wishX + v.z * wishZ;
        double addSpeed = wishSpd - currentSpeed;
        if (addSpeed <= 0.0) return;
        double accelSpeed = accel * wishSpeed * s.dt * s.surfaceFriction;
        if (accelSpeed > addSpeed) accelSpeed = addSpeed;
        v.x += accelSpeed * wishX;
        v.z += accelSpeed * wishZ;
    }

    /** {@code StayOnGround} (1859-1890): keep a walking player glued to slopes and down small steps. */
    private static void stayOnGround(Scratch s) {
        CsVec origin = s.state.origin;
        CsTrace tr = s.trace2;
        double stepSize = s.input.stepHeight();
        trace(s, origin.x, origin.y, origin.z, origin.x, origin.y + 2.0, origin.z, tr);
        double startX = tr.end.x, startY = tr.end.y, startZ = tr.end.z;
        trace(s, startX, startY, startZ, origin.x, origin.y - stepSize, origin.z, tr);
        if (tr.fraction > 0.0 && tr.fraction < 1.0 && !tr.startSolid && tr.normal.y >= WALKABLE_NORMAL) {
            double delta = Math.abs(origin.y - tr.end.y);
            if (delta > 0.5 * COORD_RESOLUTION) {
                origin.set(tr.end);
            }
        }
    }

    /** {@code StepMove} (1517-1607): slide on the ground or step up by {@code stepHeight}, whichever goes further. */
    private static void stepMove(Scratch s, double destX, double destY, double destZ) {
        CsPlayerState state = s.state;
        CsVec origin = state.origin;
        CsVec v = state.velocity;
        double posX = origin.x, posY = origin.y, posZ = origin.z;
        double velX = v.x, velY = v.y, velZ = v.z;

        // Slide move down.
        tryPlayerMove(s);
        double downX = origin.x, downY = origin.y, downZ = origin.z;
        double downVelX = v.x, downVelY = v.y, downVelZ = v.z;

        // Reset and move up a stair height.
        origin.set(posX, posY, posZ);
        v.set(velX, velY, velZ);
        CsTrace tr = s.trace2;
        double step = s.input.stepHeight() + ConvexBrushWorld.DIST_EPSILON;
        trace(s, origin.x, origin.y, origin.z, origin.x, origin.y + step, origin.z, tr);
        if (!tr.startSolid && !tr.allSolid) {
            origin.set(tr.end);
        }

        // Slide move up.
        tryPlayerMove(s);

        // Move down a stair (attempt to).
        trace(s, origin.x, origin.y, origin.z, origin.x, origin.y - step, origin.z, tr);
        if (tr.normal.y < WALKABLE_NORMAL) {
            origin.set(downX, downY, downZ);
            v.set(downVelX, downVelY, downVelZ);
            return;
        }
        if (!tr.startSolid && !tr.allSolid) {
            origin.set(tr.end);
        }
        double upX = origin.x, upZ = origin.z;
        double downDist = (downX - posX) * (downX - posX) + (downZ - posZ) * (downZ - posZ);
        double upDist = (upX - posX) * (upX - posX) + (upZ - posZ) * (upZ - posZ);
        if (downDist > upDist) {
            origin.set(downX, downY, downZ);
            v.set(downVelX, downVelY, downVelZ);
        } else {
            v.y = downVelY;
        }
    }

    /**
     * {@code TryPlayerMove} (2560-2814): up to 4 bumps against up to 5 clip planes; the first airborne impact uses
     * the sv_bounce reflection branch (overbounce 1 at sv_bounce 0), later impacts clip against every plane or slide
     * along the crease of two, and stop if the result opposes the original velocity.
     */
    private static void tryPlayerMove(Scratch s) {
        CsPlayerState state = s.state;
        CsVec origin = state.origin;
        CsVec v = state.velocity;
        double[] px = s.planeX, py = s.planeY, pz = s.planeZ;
        CsTrace pm = s.trace;
        CsTrace stuck = s.trace2;

        double origX = v.x, origY = v.y, origZ = v.z;
        double primalX = v.x, primalY = v.y, primalZ = v.z;
        int numPlanes = 0;
        double allFraction = 0.0;
        double timeLeft = s.dt;

        for (int bump = 0; bump < MAX_BUMPS; bump++) {
            if (v.x == 0.0 && v.y == 0.0 && v.z == 0.0) break;
            double endX = origin.x + timeLeft * v.x;
            double endY = origin.y + timeLeft * v.y;
            double endZ = origin.z + timeLeft * v.z;
            trace(s, origin.x, origin.y, origin.z, endX, endY, endZ, pm);
            allFraction += pm.fraction;

            if (pm.allSolid) {
                v.set(0.0, 0.0, 0.0);
                return;
            }
            if (pm.fraction > 0.0) {
                if (pm.fraction == 1.0) {
                    // Re-test the end unswept; refuse a move that would end stuck (gamemovement.cpp 2636-2651).
                    trace(s, pm.end.x, pm.end.y, pm.end.z, pm.end.x, pm.end.y, pm.end.z, stuck);
                    if (stuck.startSolid || stuck.fraction != 1.0) {
                        v.set(0.0, 0.0, 0.0);
                        break;
                    }
                }
                origin.set(pm.end);
                origX = v.x;
                origY = v.y;
                origZ = v.z;
                numPlanes = 0;
            }
            if (pm.fraction == 1.0) break;

            double nx = pm.normal.x, ny = pm.normal.y, nz = pm.normal.z;
            if (ny > 0.0 && ny < WALKABLE_NORMAL) {
                state.surfing = true;
            }
            timeLeft -= timeLeft * pm.fraction;

            if (numPlanes >= MAX_CLIP_PLANES) {
                v.set(0.0, 0.0, 0.0);
                break;
            }
            px[numPlanes] = nx;
            py[numPlanes] = ny;
            pz[numPlanes] = nz;
            numPlanes++;

            if (numPlanes == 1 && !state.grounded) {
                // Reflection branch; with sv_bounce 0 both floor and wall use overbounce 1.
                clipVelocity(origX, origY, origZ, px[0], py[0], pz[0], 1.0, s.clip);
                v.set(s.clip);
                origX = s.clip.x;
                origY = s.clip.y;
                origZ = s.clip.z;
            } else {
                int i;
                for (i = 0; i < numPlanes; i++) {
                    clipVelocity(origX, origY, origZ, px[i], py[i], pz[i], 1.0, v);
                    int j;
                    for (j = 0; j < numPlanes; j++) {
                        if (j != i && v.x * px[j] + v.y * py[j] + v.z * pz[j] < 0.0) break;
                    }
                    if (j == numPlanes) break;
                }
                if (i == numPlanes) {
                    // Go along the crease.
                    if (numPlanes != 2) {
                        v.set(0.0, 0.0, 0.0);
                        break;
                    }
                    double dx = py[0] * pz[1] - pz[0] * py[1];
                    double dy = pz[0] * px[1] - px[0] * pz[1];
                    double dz = px[0] * py[1] - py[0] * px[1];
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (len > 0.0) {
                        dx /= len;
                        dy /= len;
                        dz /= len;
                    }
                    double d = dx * v.x + dy * v.y + dz * v.z;
                    v.set(dx * d, dy * d, dz * d);
                }
                // Moving against the original velocity: stop dead to avoid oscillation in sloping corners.
                if (v.x * primalX + v.y * primalY + v.z * primalZ <= 0.0) {
                    v.set(0.0, 0.0, 0.0);
                    break;
                }
            }
        }
        if (allFraction == 0.0) {
            v.set(0.0, 0.0, 0.0);
        }
    }

    /** {@code ClipVelocity} (3145-3180): remove the into-plane component (times overbounce), then once more. */
    private static void clipVelocity(double inX, double inY, double inZ, double nx, double ny, double nz,
            double overbounce, CsVec out) {
        double backoff = (inX * nx + inY * ny + inZ * nz) * overbounce;
        double ox = inX - nx * backoff;
        double oy = inY - ny * backoff;
        double oz = inZ - nz * backoff;
        double adjust = ox * nx + oy * ny + oz * nz;
        if (adjust < 0.0) {
            ox -= nx * adjust;
            oy -= ny * adjust;
            oz -= nz * adjust;
        }
        out.set(ox, oy, oz);
    }

    /**
     * {@code CategorizePosition} (3774-3893) with {@code TryTouchGroundInQuadrants} (3711-3768): ground iff a 2-unit
     * probe (or one of four quarter hulls) finds a plane with normal y >= 0.7 and the player is not rising faster
     * than 140 u/s. The origin is not moved.
     */
    private static void categorize(Scratch s) {
        CsPlayerState state = s.state;
        CsVec origin = state.origin;
        if (state.velocity.y > NON_JUMP_VELOCITY) {
            state.grounded = false;
            return;
        }
        double hw = s.input.halfWidth();
        double h = s.input.height();
        CsTrace pm = s.trace;
        trace(s, origin.x, origin.y, origin.z, origin.x, origin.y - GROUND_PROBE, origin.z, pm);
        if (pm.hit() && pm.normal.y >= WALKABLE_NORMAL) {
            setGround(state, pm.normal.y);
            return;
        }
        if (pm.hit() && pm.normal.y > 0.0) {
            state.surfing = true;
        }
        // Quarter hulls: (-x,-z), (+x,+z), (-x,+z), (+x,-z), each half as wide, centred in its quadrant.
        double q = hw * 0.5;
        if (quadrantGround(s, origin.x - q, origin.y, origin.z - q, q, h)
                || quadrantGround(s, origin.x + q, origin.y, origin.z + q, q, h)
                || quadrantGround(s, origin.x - q, origin.y, origin.z + q, q, h)
                || quadrantGround(s, origin.x + q, origin.y, origin.z - q, q, h)) {
            setGround(state, pm.normal.y);
            return;
        }
        state.grounded = false;
    }

    private static boolean quadrantGround(Scratch s, double x, double y, double z, double halfWidth, double height) {
        CsTrace pm = s.trace;
        s.world.traceHull(x, y, z, x, y - GROUND_PROBE, z, halfWidth, height, pm);
        return pm.hit() && pm.normal.y >= WALKABLE_NORMAL;
    }

    /** {@code SetGroundEntity} with a ground plane: zero vertical velocity. */
    private static void setGround(CsPlayerState state, double normalY) {
        state.grounded = true;
        state.groundNormalY = normalY;
        state.velocity.y = 0.0;
    }

    private static void trace(Scratch s, double sx, double sy, double sz, double ex, double ey, double ez,
            CsTrace out) {
        s.world.traceHull(sx, sy, sz, ex, ey, ez, s.input.halfWidth(), s.input.height(), out);
    }
}
