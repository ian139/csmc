package dev.csmc.surf;

import dev.csmc.CsmcMod;
import dev.csmc.ballistics.Ballistics;
import dev.csmc.ballistics.TraceResult;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.equipment.EquipmentRegistry;
import dev.csmc.equipment.PlantedC4Entity;
import dev.csmc.grenade.GrenadeEffects;
import dev.csmc.network.KnifeActionPayload;
import dev.csmc.melee.MeleeSystem;
import dev.csmc.movement.CsMovement;
import dev.csmc.movement.MovementMode;
import dev.csmc.movement.geometry.AnalyticCollision;
import dev.csmc.movement.geometry.BrushPlane;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real player contexts must not turn surf cover into air when CS movement is enabled. */
public final class SurfCombatGameTests implements FabricGameTest {
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "surf_cover")
    public void bulletsSeeRampCoverWithRealShooter(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            for (MovementMode mode : List.of(MovementMode.OFF, MovementMode.SURF)) {
                mode(player, mode);
                wall(helper, true);
                TraceResult trace = Ballistics.trace(helper.getLevel(), player, player.getEyePosition(),
                        new Vec3(0, 0, 1), WeaponRegistry.get(CsmcMod.id("ak47")));
                helper.assertTrue(trace.impacts().stream().anyMatch(i -> i instanceof TraceResult.BlockImpact b
                                && b.state().is(Surf.SURF_RAMP)),
                        mode + ": real-shooter bullet must hit the ramp");
                helper.assertTrue(trace.stopReason() != TraceResult.StopReason.RANGE
                                || trace.remainingDamage() < WeaponRegistry.get(CsmcMod.id("ak47")).damage(),
                        mode + ": ramp must stop or attenuate the bullet");
            }
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "surf_cover")
    public void knifeCannotHitThroughRamp(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().setItem(0, new ItemStack(CsmcMod.KNIVES.get("knife")));
        var target = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(1.5, 1, 3.9));
        target.setNoGravity(true);
        try {
            for (MovementMode mode : List.of(MovementMode.OFF, MovementMode.SURF)) {
                mode(player, mode);
                wall(helper, true);
                MeleeSystem.reset(player);
                helper.assertValueEqual(MeleeSystem.handle(player, KnifeActionPayload.SLASH).outcome(),
                        MeleeSystem.Outcome.HITWALL, mode + ": ramp must obstruct the knife");
                helper.assertTrue(target.getHealth() == target.getMaxHealth(), "target behind cover is unharmed");
            }
            wall(helper, false);
            MeleeSystem.reset(player);
            helper.assertValueEqual(MeleeSystem.handle(player, KnifeActionPayload.SLASH).outcome(),
                    MeleeSystem.Outcome.HIT, "fixture is in reach without cover");
        } finally { target.discard(); remove(player); }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "surf_cover")
    public void flashLineOfSightSeesRamp(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Vec3 eye = player.getEyePosition();
        Vec3 flash = eye.add(0, 0, 3);
        try {
            for (MovementMode mode : List.of(MovementMode.OFF, MovementMode.SURF)) {
                mode(player, mode);
                wall(helper, true);
                helper.assertTrue(GrenadeEffects.flashSeverity(helper.getLevel(), flash, eye,
                        new Vec3(0, 0, 1), player) == 0, mode + ": ramp blocks flash LOS");
            }
            wall(helper, false);
            helper.assertTrue(GrenadeEffects.flashSeverity(helper.getLevel(), flash, eye,
                    new Vec3(0, 0, 1), player) > 0, "uncovered fixture is flashed");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "surf_cover")
    public void explosionExposureSeesRamp(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Vec3 blast = player.position().add(0, 1, 3);
        try {
            for (MovementMode mode : List.of(MovementMode.OFF, MovementMode.SURF)) {
                mode(player, mode);
                wall(helper, false);
                double open = Explosion.getSeenPercent(blast, player);
                wall(helper, true);
                double covered = Explosion.getSeenPercent(blast, player);
                helper.assertTrue(open > 0 && covered < open,
                        mode + ": HE/C4 exposure must be reduced by the ramp: " + covered + "/" + open);
            }
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "surf_cover")
    public void c4CannotBeDefusedThroughRamp(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        PlantedC4Entity bomb = new PlantedC4Entity(EquipmentRegistry.PLANTED_C4, helper.getLevel());
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1, 3.8));
        bomb.setPos(at);
        helper.getLevel().addFreshEntity(bomb);
        Vec3 aim = bomb.getBoundingBox().getCenter().subtract(player.getEyePosition());
        player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z))));
        try {
            for (MovementMode mode : List.of(MovementMode.OFF, MovementMode.SURF)) {
                mode(player, mode);
                wall(helper, false);
                helper.assertTrue(bomb.canDefuse(player), mode + ": uncovered bomb is defusable");
                wall(helper, true);
                helper.assertFalse(bomb.canDefuse(player), mode + ": ramp blocks defuse visibility");
            }
        } finally { bomb.discard(); remove(player); }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "surf_cover")
    public void serverKeepsCrouchedPoseAfterSneakReleaseUnderRamp(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            mode(player, MovementMode.SURF);
            helper.assertTrue(SurfRamps.place(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)),
                    List.of(BrushPlane.of(0, -1, 0.1, -0.65))), "place analytic ceiling");
            player.setPose(Pose.CROUCHING);
            player.setShiftKeyDown(false);
            player.doTick();
            helper.assertValueEqual(player.getPose(), Pose.CROUCHING, "server must not stand into analytic ceiling");
            helper.assertTrue(Math.abs(player.getBoundingBox().getYsize() - player.getDimensions(Pose.CROUCHING).height())
                    < 1.0E-6, "authoritative dimensions must remain crouched");
        } finally { remove(player); }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "surf_cover")
    public void rampStaircaseDoesNotInterfereWithAnalyticMoveReplay(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            mode(player, MovementMode.SURF);
            for (int z = 1; z <= 3; z++) SurfRamps.place(helper.getLevel(),
                    helper.absolutePos(new BlockPos(1, 4 - z, z)), List.of(BrushPlane.of(0, 1, 1, 1)));
            Vec3 start = helper.absoluteVec(new Vec3(1.5, 3.8001, 1.5));
            player.setPos(start);
            var hull = player.getBoundingBox();
            Vec3 slide = new Vec3(0, -1, 1);
            for (int i = 0; i <= 20; i++) {
                helper.assertFalse(helper.getLevel().getBlockCollisions(null, hull.move(slide.scale(i / 20.0)))
                        .iterator().hasNext(), "resting/sliding hull never overlaps the inscribed staircase");
            }
            // Vanilla resolves Y first, which would hit the staircase before the horizontal slide.
            player.move(net.minecraft.world.entity.MoverType.PLAYER, slide);
            helper.assertTrue(player.position().distanceToSqr(start.add(slide)) < 1.0E-12,
                    "server axis-separated replay must omit analytic staircase boxes");
        } finally { remove(player); }
        helper.succeed();
    }

    private static void wall(GameTestHelper helper, boolean present) {
        for (int x = 0; x < 3; x++) for (int y = 1; y <= 3; y++) {
            BlockPos pos = new BlockPos(x, y, 3);
            if (present) SurfRamps.place(helper.getLevel(), helper.absolutePos(pos),
                    List.of(BrushPlane.of(0, 1, 2, 2)));
            else helper.setBlock(pos, Blocks.AIR);
        }
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        player.moveTo(at.x, at.y, at.z, 0, 0);
        player.setNoGravity(true);
        return player;
    }

    private static void mode(ServerPlayer player, MovementMode mode) {
        player.serverLevel().getGameRules().getRule(CsMovement.RULE).set(mode, player.server);
        if (AnalyticCollision.appliesTo(player) != (mode != MovementMode.OFF))
            throw new AssertionError("fixture movement predicate disagrees with " + mode);
    }

    private static void remove(ServerPlayer player) {
        mode(player, MovementMode.OFF);
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
