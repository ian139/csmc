package dev.csmc.movement;

import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.grenade.GrenadeRegistry;
import dev.csmc.movement.geometry.AnalyticCollision;
import dev.csmc.movement.geometry.BrushPlane;
import dev.csmc.movement.geometry.ConvexBrush;
import dev.csmc.movement.geometry.MovementGeometry;
import dev.csmc.movement.physics.ConvexBrushWorld;
import dev.csmc.movement.physics.CsGameMovement;
import dev.csmc.movement.physics.CsMoveInput;
import dev.csmc.movement.physics.CsMovementParams;
import dev.csmc.movement.physics.CsPlayerState;
import dev.csmc.movement.physics.CsTrace;
import dev.csmc.movement.physics.CsUnits;
import dev.csmc.network.GunActionPayload;
import dev.csmc.server.GunSystem;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.gamerule.v1.rule.EnumRule;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side CS movement rules. Gamerule switches are made and restored inside one synchronous call so tests
 * running concurrently in the same world never observe CS movement.
 */
public final class MovementGameTests implements FabricGameTest {
    private static final AtomicBoolean PROVIDER = new AtomicBoolean();

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void gameruleDefaultsOffAndSelectsPresets(GameTestHelper helper) {
        ServerPlayer player = player(helper, new Vec3(0.5, 2.0, 0.5));
        EnumRule<MovementMode> rule = rule(player);
        helper.assertValueEqual(rule.get(), MovementMode.OFF, "csmcMovement defaults to off");
        helper.assertTrue(!CsMovement.active(player) && CsMovement.params(player) == null, "off: vanilla movement");
        helper.assertTrue(!AnalyticCollision.appliesTo(player), "off: players collide with vanilla shapes");
        CsMovementParams competitive;
        CsMovementParams bhop;
        CsMovementParams surf;
        boolean analytic;
        try {
            rule.set(MovementMode.COMPETITIVE, player.server);
            competitive = CsMovement.params(player);
            analytic = AnalyticCollision.appliesTo(player);
            rule.set(MovementMode.BHOP, player.server);
            bhop = CsMovement.params(player);
            rule.set(MovementMode.SURF, player.server);
            surf = CsMovement.params(player);
        } finally {
            rule.set(MovementMode.OFF, player.server);
        }
        helper.assertValueEqual(competitive, CsMovementParams.COMPETITIVE, "competitive preset");
        helper.assertValueEqual(bhop, CsMovementParams.BHOP, "bhop preset");
        helper.assertValueEqual(surf, CsMovementParams.SURF, "surf preset");
        helper.assertTrue(analytic, "CS players collide with movement geometry analytically");
        helper.assertValueEqual(MovementMode.BHOP.toString(), "bhop", "command value is lower case");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void playerTagOverridesTheWorldMode(GameTestHelper helper) {
        ServerPlayer player = player(helper, new Vec3(0.5, 2.0, 0.5));
        EnumRule<MovementMode> rule = rule(player);
        CsMovementParams bhop;
        CsMovementParams competitive;
        CsMovementParams off;
        CsMovementParams fallback;
        try {
            rule.set(MovementMode.COMPETITIVE, player.server);
            player.addTag(CsMovement.OVERRIDE_TAG_PREFIX + "bhop");
            bhop = CsMovement.params(player);
            player.removeTag(CsMovement.OVERRIDE_TAG_PREFIX + "bhop");
            player.addTag(CsMovement.OVERRIDE_TAG_PREFIX + "off");
            off = CsMovement.params(player);
            player.removeTag(CsMovement.OVERRIDE_TAG_PREFIX + "off");
            player.addTag(CsMovement.OVERRIDE_TAG_PREFIX + "nonsense");
            fallback = CsMovement.params(player);
            player.removeTag(CsMovement.OVERRIDE_TAG_PREFIX + "nonsense");
            competitive = CsMovement.params(player);
        } finally {
            rule.set(MovementMode.OFF, player.server);
        }
        helper.assertValueEqual(bhop, CsMovementParams.BHOP, "bhop tag overrides the competitive world");
        helper.assertTrue(off == null, "off tag disables CS movement for that player");
        helper.assertValueEqual(fallback, CsMovementParams.COMPETITIVE, "unknown tag value falls back to the gamerule");
        helper.assertValueEqual(competitive, CsMovementParams.COMPETITIVE, "no tag uses the gamerule");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void heldMaxSpeedFollowsMainHandItem(GameTestHelper helper) {
        ServerPlayer player = player(helper, new Vec3(0.5, 2.0, 0.5));
        player.getInventory().selected = 0;
        helper.assertValueEqual(CsMovement.heldMaxSpeed(player), 250.0, "empty hand runs at 250 u/s");
        hold(player, new ItemStack(Items.STONE));
        helper.assertValueEqual(CsMovement.heldMaxSpeed(player), 250.0, "other items run at 250 u/s");
        hold(player, new ItemStack(CsmcMod.KNIVES.values().iterator().next()));
        helper.assertValueEqual(CsMovement.heldMaxSpeed(player), 250.0, "knives run at 250 u/s");
        hold(player, new ItemStack(GrenadeRegistry.ITEMS.values().iterator().next()));
        helper.assertValueEqual(CsMovement.heldMaxSpeed(player), 245.0, "grenades run at 245 u/s");
        hold(player, new ItemStack(CsmcMod.AK47));
        helper.assertValueEqual(CsMovement.heldMaxSpeed(player), WeaponRegistry.get(CsmcMod.AK47.weaponId()).maxSpeed(),
                "guns run at their max_speed");

        var awp = CsmcMod.GUNS.get("awp");
        ItemStack awpStack = new ItemStack(awp);
        awpStack.set(CsmcMod.MAGAZINE, WeaponRegistry.get(awp.weaponId()).magazineSize());
        hold(player, awpStack);
        helper.assertValueEqual(CsMovement.heldMaxSpeed(player), 200.0, "AWP max_speed 200");
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(GunSystem.scopeLevel(player) > 0, "AWP scoped");
            helper.assertValueEqual(CsMovement.heldMaxSpeed(player), 200.0, "held max speed stays unscoped");
            double cs = CsMovement.heldMaxSpeed(player) * speed.getValue() / speed.getBaseValue();
            helper.assertTrue(Math.abs(cs - 100.0) < 1.0E-9, "scope modifier turns it into the scoped 100 u/s: " + cs);
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void crouchingCsPlayerWalksOffLedges(GameTestHelper helper) {
        BlockPos platform = new BlockPos(1, 3, 1);
        helper.setBlock(platform, Blocks.STONE);
        Vec3 edge = new Vec3(1.95, 4.0, 1.5);
        ServerPlayer vanilla = player(helper, edge);
        vanilla.setShiftKeyDown(true);
        vanilla.setOnGround(true);
        double startX = vanilla.getX();
        vanilla.move(MoverType.PLAYER, new Vec3(0.5, 0.0, 0.0));
        double vanillaMoved = vanilla.getX() - startX;
        remove(vanilla);

        ServerPlayer cs = player(helper, edge);
        cs.setShiftKeyDown(true);
        cs.setOnGround(true);
        EnumRule<MovementMode> rule = rule(cs);
        double csMoved;
        try {
            rule.set(MovementMode.COMPETITIVE, cs.server);
            cs.move(MoverType.PLAYER, new Vec3(0.5, 0.0, 0.0));
            csMoved = cs.getX() - startX;
        } finally {
            rule.set(MovementMode.OFF, cs.server);
        }
        remove(cs);
        // Vanilla sneaking stops with the hull still over the ledge; CS ducking walks the whole way off it.
        double ledgeEdge = helper.absolutePos(platform).getX() + 1.0;
        helper.assertTrue(startX + vanillaMoved - 0.3 <= ledgeEdge + 1.0E-6,
                "vanilla sneaking backs off the ledge: moved " + vanillaMoved);
        helper.assertTrue(Math.abs(csMoved - 0.5) < 1.0E-9 && startX + csMoved - 0.3 > ledgeEdge,
                "CS ducking walks off the ledge: moved " + csMoved);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void fallDistanceClearsOnlyOnUpwardMovementSurfaces(GameTestHelper helper) {
        registerTestGeometry();
        BlockPos cube = new BlockPos(3, 3, 3);
        helper.setBlock(cube, Blocks.LIGHT);
        BlockPos wedge = new BlockPos(5, 3, 5);
        helper.setBlock(wedge, Blocks.STRUCTURE_VOID);
        BlockPos c = helper.absolutePos(cube);
        BlockPos w = helper.absolutePos(wedge);
        // Hull 0.01 blocks beside the cube's west face, under its underside, on its top, and 0.2 above its top.
        Vec3 beside = new Vec3(c.getX() - 0.31, c.getY(), c.getZ() + 0.5);
        Vec3 below = new Vec3(c.getX() + 0.5, c.getY() - 1.81, c.getZ() + 0.5);
        Vec3 onTop = new Vec3(c.getX() + 0.5, c.getY() + 1.01, c.getZ() + 0.5);
        Vec3 aboveTop = new Vec3(c.getX() + 0.5, c.getY() + 1.2, c.getZ() + 0.5);
        // Resting on the 45° wedge x + y <= wx + wy + 1: lowest hull corner (minX, minY) 0.01 above the slope.
        Vec3 onSlope = new Vec3(w.getX() + 0.5, w.getY() + 0.8 + 0.01, w.getZ() + 0.5);
        ServerPlayer player = player(helper, new Vec3(0.5, 2.0, 0.5));
        player.setGameMode(GameType.SURVIVAL);
        EnumRule<MovementMode> rule = rule(player);

        float offOnTop = fallAt(player, onTop);
        float onBeside;
        float onBelow;
        float onOnTop;
        float onAboveTop;
        float onOnSlope;
        try {
            rule.set(MovementMode.SURF, player.server);
            onBeside = fallAt(player, beside);
            onBelow = fallAt(player, below);
            onOnTop = fallAt(player, onTop);
            onAboveTop = fallAt(player, aboveTop);
            onOnSlope = fallAt(player, onSlope);
        } finally {
            rule.set(MovementMode.OFF, player.server);
        }
        helper.assertValueEqual(offOnTop, 5.5F, "movement off: fall distance accumulates as in vanilla");
        helper.assertValueEqual(onBeside, 5.5F, "beside a vertical movement-solid face: fall distance is kept");
        helper.assertValueEqual(onBelow, 5.5F, "under a movement solid's underside: fall distance is kept");
        helper.assertValueEqual(onAboveTop, 5.5F, "0.2 blocks above a movement surface: fall distance is kept");
        helper.assertValueEqual(onOnTop, 0.0F, "resting on a movement solid's top clears fall distance");
        helper.assertValueEqual(onOnSlope, 0.0F, "sliding on an upward-facing ramp clears fall distance");

        // Past the 60-tick join invulnerability: a fall that passes a wall still hurts on landing.
        for (int i = 0; i < 61; i++) player.tick();
        float healthBefore = player.getHealth();
        float afterWall;
        try {
            rule.set(MovementMode.SURF, player.server);
            player.teleportTo(beside.x, beside.y, beside.z);
            player.fallDistance = 10.0F;
            player.doCheckFallDamage(0.0, -0.5, 0.0, false);
            afterWall = player.fallDistance;
            player.doCheckFallDamage(0.0, -0.1, 0.0, true);
        } finally {
            rule.set(MovementMode.OFF, player.server);
        }
        helper.assertValueEqual(afterWall, 10.5F, "passing the wall keeps the fall");
        helper.assertValueEqual(player.fallDistance, 0.0F, "landing consumes the fall");
        helper.assertTrue(player.getHealth() < healthBefore,
                "the landing after the wall deals fall damage: " + healthBefore + " -> " + player.getHealth());
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void invalidMovementStatePayloadIsIgnored(GameTestHelper helper) {
        RegistryAccess access = helper.getLevel().registryAccess();
        CsMovementParams surf = CsMovementParams.SURF;
        for (CsMovementParams preset : new CsMovementParams[] {CsMovementParams.COMPETITIVE, CsMovementParams.BHOP,
                surf}) {
            MovementStatePayload decoded = roundTrip(access, new MovementStatePayload(preset, 250.0));
            helper.assertValueEqual(decoded.params(), preset, "preset round-trips");
            helper.assertTrue(decoded.validationError() == null, "presets are valid: " + decoded.validationError());
        }
        helper.assertTrue(roundTrip(access, new MovementStatePayload(null, 250.0)).validationError() == null,
                "off is valid");
        MovementStatePayload[] invalid = {
                new MovementStatePayload(withTickRate(surf, Integer.MAX_VALUE), 250.0),
                new MovementStatePayload(withTickRate(surf, 0), 250.0),
                new MovementStatePayload(withTickRate(surf, 129), 250.0),
                new MovementStatePayload(new CsMovementParams(Double.NaN, 10, 150, 30, 5.2, 80, 3500, 301.993377,
                        true, true, 0, 0, 0, 60, 0.34, 0.52, 85), 250.0),
                new MovementStatePayload(new CsMovementParams(800, 10, 150, 30, 5.2, 80, Double.POSITIVE_INFINITY,
                        301.993377, true, true, 0, 0, 0, 60, 0.34, 0.52, 85), 250.0),
                new MovementStatePayload(new CsMovementParams(800, 10, 150, 30, 5.2, 80, -3500, 301.993377, true,
                        true, 0, 0, 0, 60, 0.34, 0.52, 85), 250.0),
                new MovementStatePayload(new CsMovementParams(800, 10, 150, 30, 5.2, 80, 3500, 1.0E9, true, true,
                        0, 0, 0, 60, 0.34, 0.52, 85), 250.0),
                new MovementStatePayload(new CsMovementParams(800, 10, 150, 30, 5.2, 80, 3500, 301.993377, true,
                        true, 0, 0, 0, 60, 2.0, 0.52, 85), 250.0),
                new MovementStatePayload(surf, Double.NaN),
                new MovementStatePayload(surf, Double.POSITIVE_INFINITY),
                new MovementStatePayload(surf, -250.0),
        };
        CsMovement.setClientState(surf, 230.0);
        try {
            for (MovementStatePayload payload : invalid) {
                MovementStatePayload decoded = roundTrip(access, payload);
                helper.assertTrue(decoded.validationError() != null, "rejected: " + payload);
                helper.assertTrue(!CsMovement.acceptClientState(decoded), "not applied: " + payload);
                helper.assertValueEqual(CsMovement.clientParams(), surf, "previous params kept");
                helper.assertValueEqual(CsMovement.clientHeldMaxSpeed(), 230.0, "previous speed kept");
            }
            helper.assertTrue(CsMovement.acceptClientState(new MovementStatePayload(CsMovementParams.BHOP, 245.0)),
                    "a valid payload applies");
            helper.assertValueEqual(CsMovement.clientParams(), CsMovementParams.BHOP, "valid params applied");
            helper.assertValueEqual(CsMovementParams.BHOP.substepsPerTick(), 6, "128 tick: 6 substeps");
        } finally {
            CsMovement.setClientState(null, CsMovement.DEFAULT_MAX_SPEED);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void heldSpeedDomainAndModeOffRoundTrip(GameTestHelper helper) {
        RegistryAccess access = helper.getLevel().registryAccess();
        try {
            for (double speed : new double[] {1_500, 2_000}) {
                MovementStatePayload decoded = roundTrip(access, new MovementStatePayload(CsMovementParams.SURF, speed));
                helper.assertTrue(CsMovement.acceptClientState(decoded), "valid override speed applies: " + speed);
                helper.assertTrue(CsMovement.clientHeldMaxSpeed() == speed, "speed round-trips");
            }
            helper.assertTrue(new MovementStatePayload(CsMovementParams.SURF, Math.nextUp(2_000.0))
                    .validationError() != null, "over-bound speed rejected");
            for (double speed : new double[] {20_000, 1.0E9, Double.NaN, Double.POSITIVE_INFINITY, -1}) {
                helper.assertTrue(CsMovement.acceptClientState(roundTrip(access, new MovementStatePayload(null, speed))),
                        "mode off always applies regardless of unused speed: " + speed);
                helper.assertTrue(CsMovement.clientParams() == null, "mode is off");
            }
        } finally {
            CsMovement.setClientState(null, CsMovement.DEFAULT_MAX_SPEED);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void bunchedCsMovePacketsAreAcceptedAndAbsurdOnesRejected(GameTestHelper helper) {
        ServerPlayer player = player(helper, new Vec3(0.5, 2.0, 0.5));
        player.setGameMode(GameType.SURVIVAL);
        EnumRule<MovementMode> rule = rule(player);
        // Six client ticks at 4 blocks/tick (3200 u/s, under sv_maxvelocity 3500) arriving in one server tick,
        // straight up into open air.
        double[] off = bunched(player, 6, 4.0);
        double[] cs;
        double[] absurd;
        try {
            rule.set(MovementMode.SURF, player.server);
            cs = bunched(player, 6, 4.0);
            absurd = bunched(player, 1, 60.0);
            helper.assertValueEqual(CsMovement.moveBudget(player, 100.0F, 10),
                    CsMovement.moveBudget(player, 100.0F, 10_000), "packet spam does not enlarge the bounded allowance");
        } finally {
            rule.set(MovementMode.OFF, player.server);
        }
        remove(player);
        helper.assertTrue(off[1] < off[0] + 24.0 - 1.0E-6,
                "movement off: vanilla still rejects the sixth bunched packet: " + off[0] + " -> " + off[1]);
        helper.assertTrue(Math.abs(cs[1] - (cs[0] + 24.0)) < 1.0E-9,
                "CS movement: all six bunched packets accepted: " + cs[0] + " -> " + cs[1]);
        helper.assertTrue(Math.abs(absurd[1] - absurd[0]) < 1.0E-9,
                "CS movement: a 60-block packet is still rejected: " + absurd[0] + " -> " + absurd[1]);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void depenetrationLiftsHullOffInscribedSurface(GameTestHelper helper) {
        Vec3 o = helper.absoluteVec(Vec3.ZERO);
        // Analytic solid whose true top is y = 1.5; players without CS movement stand on its inscribed top 31/64.
        ConvexBrush slab = ConvexBrush.box(new AABB(o.x + 1, o.y + 1, o.z + 1, o.x + 2, o.y + 1.5, o.z + 2));
        List<ConvexBrush> brushes = List.of(slab);
        ConvexBrushWorld world = new ConvexBrushWorld(brushes);
        CsTrace trace = new CsTrace();
        AABB box = hull(o.x + 1.5, o.y + 1 + 31.0 / 64.0, o.z + 1.5);
        CsPlayerState state = new CsPlayerState();
        state.origin.set(CsUnits.toUnits(o.x + 1.5), CsUnits.toUnits(box.minY), CsUnits.toUnits(o.z + 1.5));
        CsGameMovement.categorizePosition(state, still(), world);
        helper.assertTrue(MovementResolve.overlaps(world, box, trace), "inscribed stance penetrates the solid");
        helper.assertTrue(!state.grounded, "and is not ground for the exact simulation");
        Vec3 push = MovementResolve.depenetrate(brushes, 0, world, box, trace);
        helper.assertTrue(push != null && push.x == 0.0 && push.z == 0.0
                && push.y >= 1.0 / 64.0 && push.y < 1.0 / 64.0 + 0.01, "pushed straight up out of the top: " + push);
        AABB lifted = box.move(push);
        helper.assertTrue(!MovementResolve.overlaps(world, lifted, trace), "clear after depenetration");
        state.origin.set(CsUnits.toUnits(o.x + 1.5), CsUnits.toUnits(lifted.minY), CsUnits.toUnits(o.z + 1.5));
        CsGameMovement.categorizePosition(state, still(), world);
        helper.assertTrue(state.grounded, "standing on the true surface after depenetration");
        helper.assertValueEqual(MovementResolve.depenetrate(brushes, 0, world, lifted, trace), Vec3.ZERO,
                "a clear hull is left alone");

        // Inside a 45° ramp x + y <= c by 0.02 blocks: pushed out along the ramp normal.
        double c = o.x + o.y + 10.0;
        ConvexBrush ramp = wedge(new AABB(o.x, o.y, o.z, o.x + 8, o.y + 8, o.z + 1), c);
        List<ConvexBrush> rampBrushes = List.of(ramp);
        ConvexBrushWorld rampWorld = new ConvexBrushWorld(rampBrushes);
        double px = o.x + 4.0;
        AABB inRamp = hull(px, c - (px - 0.3) - 0.02, o.z + 0.5);
        Vec3 rampPush = MovementResolve.depenetrate(rampBrushes, 0, rampWorld, inRamp, trace);
        helper.assertTrue(rampPush != null && Math.abs(rampPush.x - rampPush.y) < 1.0E-9 && rampPush.x > 0.0
                && rampPush.z == 0.0, "pushed along the ramp normal: " + rampPush);
        helper.assertTrue(!MovementResolve.overlaps(rampWorld, inRamp.move(rampPush), trace), "clear of the ramp");

        // Deep inside a solid: unresolvable within the bound, left to vanilla.
        ConvexBrush big = ConvexBrush.box(new AABB(o.x, o.y, o.z, o.x + 4, o.y + 4, o.z + 4));
        helper.assertTrue(MovementResolve.depenetrate(List.of(big), 0, new ConvexBrushWorld(List.of(big)),
                hull(o.x + 2, o.y + 1, o.z + 2), trace) == null, "deep penetration is not resolved");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void replayFallbackNeverEndsInsideAnalyticGeometry(GameTestHelper helper) {
        // Reduced reproduction of the review case: airborne hull at (3, 3.5) moving (3.628, -1.502) blocks in one
        // tick over a descending ramp x + y <= 6, with a stone shelf at x = [4, 5], y = [1, 2] blocking vanilla.
        BlockPos shelf = new BlockPos(4, 1, 0);
        helper.setBlock(shelf, Blocks.STONE);
        Vec3 o = helper.absoluteVec(Vec3.ZERO);
        ConvexBrush ramp = wedge(new AABB(o.x, o.y, o.z, o.x + 6, o.y + 6, o.z + 1), o.x + o.y + 6.0);
        List<ConvexBrush> brushes = List.of(ConvexBrush.box(new AABB(helper.absolutePos(shelf))), ramp);
        ConvexBrushWorld combined = new ConvexBrushWorld(brushes);
        CsTrace trace = new CsTrace();
        AABB box = hull(o.x + 3.0, o.y + 3.5, o.z + 0.5);
        Vec3 csMove = new Vec3(3.628125, -1.502343775, 0.0);
        UnaryOperator<Vec3> vanilla = move -> Entity.collideBoundingBox(null, move, box, helper.getLevel(),
                List.of());
        Vec3 replay = vanilla.apply(csMove);
        helper.assertTrue(!MovementResolve.overlaps(combined, box, trace), "start is clear");
        helper.assertTrue(Math.abs(replay.x - 0.7) < 1.0E-6, "vanilla replay stops at the shelf: " + replay);
        helper.assertTrue(MovementResolve.overlaps(combined, box.move(replay), trace),
                "committing the raw replay would embed the hull in the ramp");
        MovementResolve.Fallback fallback = MovementResolve.fallback(combined, box, replay, vanilla, 0.05, trace);
        Vec3 move = fallback.move();
        helper.assertTrue(!MovementResolve.overlaps(combined, box.move(move), trace),
                "fallback endpoint is clear of the combined world: " + move);
        Vec3 replayed = vanilla.apply(move);
        helper.assertTrue(replayed.distanceTo(move) < 0.05, "fallback is replay-compatible: " + move + " vs "
                + replayed);
        helper.assertTrue(move.y < 0.0 && fallback.normal() != null && fallback.normal().y > 0.0,
                "stopped on the ramp surface: " + move + " normal " + fallback.normal());
        ConvexBrushWorld empty = new ConvexBrushWorld(List.of());
        MovementResolve.Fallback incompatible = MovementResolve.fallback(empty, box, new Vec3(1, 0, 0),
                candidate -> Vec3.ZERO, 0.05, trace);
        helper.assertValueEqual(incompatible.move(), Vec3.ZERO,
                "even a clear raw replay must be replay-compatible before it is committed");
        helper.succeed();
    }

    /** Test-only solids: a full cube at every light block, a 45° wedge (x + y <= cell + 1) at structure voids. */
    private static void registerTestGeometry() {
        if (!PROVIDER.compareAndSet(false, true)) return;
        MovementGeometry.register((level, region, sink) -> BlockPos.betweenClosedStream(
                        BlockPos.containing(region.minX, region.minY, region.minZ),
                        BlockPos.containing(region.maxX, region.maxY, region.maxZ))
                .forEach(pos -> {
                    if (level.getBlockState(pos).is(Blocks.LIGHT)) {
                        sink.accept(ConvexBrush.box(new AABB(pos)));
                    } else if (level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
                        sink.accept(wedge(new AABB(pos), pos.getX() + pos.getY() + 1.0));
                    }
                }));
    }

    /** {@code bounds} cut by the plane {@code x + y <= c} (a ramp descending towards +x). */
    private static ConvexBrush wedge(AABB bounds, double c) {
        List<BrushPlane> planes = new ArrayList<>(ConvexBrush.box(bounds).planes());
        planes.add(BrushPlane.of(1, 1, 0, c));
        return new ConvexBrush(planes, bounds);
    }

    private static AABB hull(double x, double minY, double z) {
        return new AABB(x - 0.3, minY, z - 0.3, x + 0.3, minY + 1.8, z + 0.3);
    }

    private static CsMoveInput still() {
        return new CsMoveInput(0, 0, false, false, false, 0, 250, CsUnits.toUnits(0.3), CsUnits.toUnits(1.8),
                CsUnits.toUnits(0.6), 1.0);
    }

    private static float fallAt(ServerPlayer player, Vec3 position) {
        player.teleportTo(position.x, position.y, position.z);
        player.fallDistance = 5.0F;
        player.doCheckFallDamage(0.0, -0.5, 0.0, false);
        return player.fallDistance;
    }

    private static MovementStatePayload roundTrip(RegistryAccess access, MovementStatePayload payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        MovementStatePayload.STREAM_CODEC.encode(buf, payload);
        return MovementStatePayload.STREAM_CODEC.decode(buf);
    }

    private static CsMovementParams withTickRate(CsMovementParams p, int tickRate) {
        return new CsMovementParams(p.gravity(), p.accelerate(), p.airAccelerate(), p.airMaxWishSpeed(), p.friction(),
                p.stopSpeed(), p.maxVelocity(), p.jumpImpulse(), p.enableBunnyhopping(), p.autoBunnyhopping(),
                p.staminaMax(), p.staminaJumpCost(), p.staminaLandCost(), p.staminaRecoveryRate(),
                p.duckSpeedModifier(), p.walkSpeedModifier(), tickRate);
    }

    /**
     * Sends {@code count} position packets, each {@code step} blocks above the last, as if they all arrived in one
     * server tick, after accepting any pending teleport and starting a fresh tick window. Returns the start and
     * final Y.
     */
    private static double[] bunched(ServerPlayer player, int count, double step) {
        ServerGamePacketListenerImpl connection = player.connection;
        Object awaiting = field(connection, "awaitingPositionFromClient");
        if (awaiting != null) {
            connection.handleAcceptTeleportPacket(
                    new ServerboundAcceptTeleportationPacket((Integer) field(connection, "awaitingTeleport")));
        }
        player.setDeltaMovement(Vec3.ZERO);
        connection.resetPosition();
        // Past the first tick (tick 0 re-baselines every packet) and at the start of a server tick.
        setField(connection, "tickCount", Math.max(1, (Integer) field(connection, "tickCount")));
        setField(connection, "knownMovePacketCount", field(connection, "receivedMovePacketCount"));
        double x = player.getX();
        double start = player.getY();
        double z = player.getZ();
        for (int i = 1; i <= count; i++) {
            connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(x, start + i * step, z, false));
        }
        return new double[] {start, player.getY()};
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = ServerGamePacketListenerImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field field = ServerGamePacketListenerImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EnumRule<MovementMode> rule(ServerPlayer player) {
        return player.serverLevel().getGameRules().getRule(CsMovement.RULE);
    }

    private static void hold(ServerPlayer player, ItemStack stack) {
        player.getInventory().setItem(0, stack);
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, Vec3 relative) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(relative);
        player.teleportTo(position.x, position.y, position.z);
        player.setNoGravity(true);
        return player;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
