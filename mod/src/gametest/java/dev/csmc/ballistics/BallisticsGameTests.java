package dev.csmc.ballistics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-trace GameTests: shipped material table, real level blocks, entities and players. Weapons are
 * authored here (parsed by the real weapon parser) so a local CS2 cache can never change the expected wall
 * behavior; only the spray-recovery test uses the registry AK-47 and compares it against its own data.
 * Lanes run along +X at relative y = 2 inside the 8x8x8 empty structure; test weapons reach x = 7.5.
 */
public final class BallisticsGameTests implements FabricGameTest {
    private static final Vec3 EAST = new Vec3(1.0, 0.0, 0.0);
    private static final int LANE = 3;
    private static final int OPEN_LANE = 5;
    private static final BlockPos PANE = new BlockPos(2, 2, LANE);
    /** 7 blocks from x = 0.5: the trace ends at x = 7.5, inside the structure. */
    private static final double TEST_RANGE_UNITS = 7.0 * WeaponData.UNITS_PER_BLOCK;
    private static final double KICK_TOLERANCE_DEG = 1.0E-3;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void glassPaneShatters(GameTestHelper helper) {
        helper.setBlock(PANE, Blocks.GLASS_PANE);
        setBlockBreaking(helper, true);
        WeaponData rifle = testRifle();

        TraceResult result = trace(helper, LANE, rifle);

        helper.assertBlockPresent(Blocks.AIR, PANE);
        helper.assertValueEqual(result.stopReason(), TraceResult.StopReason.RANGE, "stop reason");
        helper.assertValueEqual(result.impacts().size(), 1, "impact count");
        assertBlockImpact(helper, result.impacts().get(0), PANE, TraceResult.BlockOutcome.SHATTERED);
        helper.assertTrue(result.remainingDamage() > 0.0 && result.remainingDamage() < rifle.damage(),
                "passing glass must cost damage, got " + result.remainingDamage());
        helper.assertValueEqual(result.remainingPenetrations(), rifle.penetrationCount() - 1, "penetrations left");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void thinWoodPenetratesWithReducedTargetDamage(GameTestHelper helper) {
        BlockPos plank = new BlockPos(2, 2, LANE);
        helper.setBlock(plank, Blocks.OAK_PLANKS);
        setBlockBreaking(helper, true);
        Zombie behindWood = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(4.5, 1.5, LANE + 0.5));
        Zombie inOpen = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(4.5, 1.5, OPEN_LANE + 0.5));
        float woodHealth = behindWood.getHealth();
        float openHealth = inOpen.getHealth();
        WeaponData rifle = testRifle();

        TraceResult throughWood = trace(helper, LANE, rifle);
        TraceResult open = trace(helper, OPEN_LANE, rifle);

        List<TraceResult.Impact> woodImpacts = throughWood.impacts();
        helper.assertTrue(woodImpacts.size() >= 2, "wood lane needs plank and zombie impacts, got " + woodImpacts);
        helper.assertTrue(woodImpacts.get(0) instanceof TraceResult.BlockImpact block
                        && block.pos().equals(helper.absolutePos(plank))
                        && block.outcome() != TraceResult.BlockOutcome.STOPPED,
                "bullet must pass the plank, got " + woodImpacts.get(0));
        TraceResult.EntityImpact woodHit = entityImpact(helper, woodImpacts.get(1), behindWood);
        TraceResult.EntityImpact openHit = entityImpact(helper, open.impacts().get(0), inOpen);
        helper.assertValueEqual(woodHit.hitGroup(), openHit.hitGroup(), "hit group");
        helper.assertTrue(woodHit.csDamage() > 0.0 && woodHit.csDamage() < openHit.csDamage(),
                "wood must reduce damage: through wood " + woodHit.csDamage() + ", open " + openHit.csDamage());
        float woodLoss = woodHealth - behindWood.getHealth();
        float openLoss = openHealth - inOpen.getHealth();
        helper.assertTrue(woodLoss > 0.0F && woodLoss < openLoss,
                "health lost through wood " + woodLoss + " must be positive and below open " + openLoss);
        behindWood.discard();
        inOpen.discard();
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void thickStoneStopsBeforePaneAndTarget(GameTestHelper helper) {
        List<BlockPos> stones = List.of(new BlockPos(2, 2, LANE), new BlockPos(3, 2, LANE), new BlockPos(4, 2, LANE));
        BlockPos pane = new BlockPos(6, 2, LANE);
        stones.forEach(stone -> helper.setBlock(stone, Blocks.STONE));
        helper.setBlock(pane, Blocks.GLASS_PANE);
        setBlockBreaking(helper, true);
        Zombie target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(5.5, 1.5, LANE + 0.5));
        float health = target.getHealth();

        TraceResult result = trace(helper, LANE, testRifle());

        helper.assertTrue(result.stopReason() == TraceResult.StopReason.POWER
                        || result.stopReason() == TraceResult.StopReason.PENETRATION,
                "thick stone must use up the bullet, got " + result.stopReason());
        Set<BlockPos> absoluteStones = Set.of(helper.absolutePos(stones.get(0)), helper.absolutePos(stones.get(1)),
                helper.absolutePos(stones.get(2)));
        helper.assertTrue(absoluteStones.contains(result.stoppedAt()), "must stop inside the stone, got " + result.stoppedAt());
        for (TraceResult.Impact impact : result.impacts()) {
            helper.assertTrue(impact instanceof TraceResult.BlockImpact block && absoluteStones.contains(block.pos()),
                    "only stone may be hit, got " + impact);
        }
        stones.forEach(stone -> helper.assertBlockPresent(Blocks.STONE, stone));
        helper.assertBlockPresent(Blocks.GLASS_PANE, pane);
        helper.assertValueEqual(target.getHealth(), health, "protected target health");
        target.discard();
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void entityHitInRayOrderBetweenPaneAndStone(GameTestHelper helper) {
        BlockPos stone = new BlockPos(5, 2, LANE);
        BlockPos stoneBehind = new BlockPos(6, 2, LANE);
        helper.setBlock(PANE, Blocks.GLASS_PANE);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(stoneBehind, Blocks.STONE);
        setBlockBreaking(helper, true);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(3.5, 1.5, LANE + 0.5));
        float healthBefore = zombie.getHealth();

        TraceResult result = trace(helper, LANE, testRifle());

        List<TraceResult.Impact> impacts = result.impacts();
        helper.assertTrue(impacts.size() >= 3, "pane, zombie and stone impacts expected, got " + impacts);
        assertBlockImpact(helper, impacts.get(0), PANE, TraceResult.BlockOutcome.SHATTERED);
        helper.assertTrue(entityImpact(helper, impacts.get(1), zombie).hurt(), "zombie must be damaged");
        // Stone takes cumulative damage below its threshold whether or not the bullet gets through it.
        assertBlockImpact(helper, impacts.get(2), stone, TraceResult.BlockOutcome.DAMAGED);
        helper.assertTrue(result.stoppedAt() != null && (result.stoppedAt().equals(helper.absolutePos(stone))
                        || result.stoppedAt().equals(helper.absolutePos(stoneBehind))),
                "two stone blocks must stop the bullet, got " + result.stopReason() + " at " + result.stoppedAt());
        helper.assertTrue(zombie.getHealth() < healthBefore, "zombie health must drop");
        for (int i = 1; i < impacts.size(); i++) {
            helper.assertTrue(impacts.get(i - 1).distance() < impacts.get(i).distance(), "impacts must be in ray order");
        }
        zombie.discard();
        helper.succeed();
    }

    /** The range ends inside a glass block: no exit loss and no penetration is used for it. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void rangeEndingInsideObstacleCostsNoExitLoss(GameTestHelper helper) {
        BlockPos glass = new BlockPos(7, 2, LANE);
        helper.setBlock(glass, Blocks.GLASS);
        setBlockBreaking(helper, true);
        WeaponData rifle = testRifle();

        TraceResult inside = trace(helper, LANE, rifle);
        TraceResult open = trace(helper, OPEN_LANE, rifle);

        helper.assertValueEqual(inside.stopReason(), TraceResult.StopReason.RANGE, "stop reason");
        helper.assertValueEqual(inside.impacts().size(), 1, "impact count");
        assertBlockImpact(helper, inside.impacts().get(0), glass, TraceResult.BlockOutcome.SHATTERED);
        helper.assertValueEqual(inside.remainingPenetrations(), rifle.penetrationCount(), "penetrations left");
        helper.assertTrue(Math.abs(inside.remainingDamage() - open.remainingDamage()) < 1.0E-9,
                "only range falloff applies: inside " + inside.remainingDamage() + ", open " + open.remainingDamage());
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unbreakableBlockStops(GameTestHelper helper) {
        BlockPos bedrock = new BlockPos(3, 2, LANE);
        helper.setBlock(bedrock, Blocks.BEDROCK);
        setBlockBreaking(helper, true);

        TraceResult result = trace(helper, LANE, testRifle());

        helper.assertBlockPresent(Blocks.BEDROCK, bedrock);
        helper.assertValueEqual(result.stopReason(), TraceResult.StopReason.BLOCK, "stop reason");
        helper.assertValueEqual(result.stoppedAt(), helper.absolutePos(bedrock), "stopped at");
        helper.succeed();
    }

    /** Own batch: batches run sequentially, so flipping the global gamerule cannot race other tests. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_ballistics_no_block_breaking")
    public void forbiddenBreakingStopsAtPane(GameTestHelper helper) {
        helper.setBlock(PANE, Blocks.GLASS_PANE);
        setBlockBreaking(helper, false);
        try {
            TraceResult result = trace(helper, LANE, testRifle());
            helper.assertBlockPresent(Blocks.GLASS_PANE, PANE);
            helper.assertValueEqual(result.stopReason(), TraceResult.StopReason.FORBIDDEN, "stop reason");
            helper.assertValueEqual(result.stoppedAt(), helper.absolutePos(PANE), "stopped at");
        } finally {
            setBlockBreaking(helper, true);
        }
        helper.succeed();
    }

    /**
     * Two real sprays per weapon produce the same view kicks: a measured pattern (last entry repeats) and the
     * seeded fallback, which must also match a separately parsed copy of the same definition. Switching
     * weapons restarts the spray.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void recoilSprayIsDeterministicAcrossFirings(GameTestHelper helper) {
        ServerPlayer player = shooter(helper);
        WeaponData patterned = recoilWeapon("test_recoil_pattern", true);
        WeaponData seeded = recoilWeapon("test_recoil_seeded", false);
        WeaponData seededCopy = recoilWeapon("test_recoil_seeded", false);

        List<WeaponData.Kick> patternFirst = spray(player, patterned, 4);
        assertKicks(helper, patternFirst, patterned, 1, "pattern spray");
        helper.assertTrue(near(patternFirst.get(3), patterned.recoil().pattern().get(2)),
                "shots past the pattern repeat its last entry, got " + patternFirst.get(3));
        List<WeaponData.Kick> seededFirst = spray(player, seeded, 5);
        assertKicks(helper, seededFirst, seeded, 1, "seeded spray after weapon switch");
        helper.assertTrue(seededFirst.stream().anyMatch(kick -> !near(kick, seededFirst.get(0))),
                "seeded variance must vary kicks within a spray, got " + seededFirst);
        for (int shot = 1; shot <= 5; shot++) {
            helper.assertValueEqual(seededCopy.recoil().kick(shot), seeded.recoil().kick(shot), "seeded kick " + shot);
        }
        long quietTicks = (long) Math.ceil(patterned.cycleTimeS() * 20.0) + Ballistics.SHOT_RESET_GRACE_TICKS;
        ShooterState state = ShooterState.of(player);
        helper.assertTrue(state.pendingPitch() != 0.0, "unechoed kicks must offset the server aim");
        Vec3 aimed = Ballistics.aimDirection(-60.0F, 0.0F, state.pendingPitch(), state.pendingYaw());
        Vec3 expected = Vec3.directionFromRotation((float) (-60.0 - state.pendingPitch()), (float) state.pendingYaw());
        helper.assertTrue(aimed.distanceTo(expected) < 1.0E-6, "aim must include the offset, got " + aimed);
        helper.runAfterDelay(quietTicks + 2, () -> {
            // Mock latency is 0: kicks count as echoed one tick after they were sent.
            helper.assertValueEqual(state.pendingPitch(), 0.0, "absorbed pitch offset");
            helper.assertValueEqual(state.pendingYaw(), 0.0, "absorbed yaw offset");
            assertSameKicks(helper, spray(player, patterned, 4), patternFirst, "second pattern spray");
            assertSameKicks(helper, spray(player, seeded, 5), seededFirst, "second seeded spray");
            remove(player);
            helper.succeed();
        });
    }

    /**
     * Holding the registry AK-47 (compared only against its own data): the spray continues across ticks
     * inside the cadence window, the fire penalty recovers to 10% over the recovery time, and the spray
     * restarts after a quiet period.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    public void sprayContinuesThenRecovers(GameTestHelper helper) {
        WeaponData ak = WeaponRegistry.get(CsmcMod.id("ak47"));
        helper.assertTrue(ak != null, "csmc:ak47 must be loaded");
        ServerPlayer player = shooter(helper);
        player.getInventory().setItem(0, new ItemStack(CsmcMod.AK47));
        player.getInventory().selected = 0;
        ShooterState state = ShooterState.of(player);

        assertKicks(helper, spray(player, ak, 2), ak, 1, "first burst");
        helper.runAfterDelay(1, () -> {
            assertKicks(helper, spray(player, ak, 1), ak, 3, "continued spray");
            helper.assertValueEqual(state.shotsFired(), 3, "spray index");
            double penalty = state.accuracyPenalty;
            helper.assertTrue(penalty >= ak.inaccuracy().fire(), "fire inaccuracy must accumulate, got " + penalty);
            long quietTicks = (long) Math.ceil(ak.cycleTimeS() * 20.0) + Ballistics.SHOT_RESET_GRACE_TICKS;
            long recoveryTicks = (long) Math.ceil(ak.recovery().timeS(false, 3) * 20.0);
            helper.runAfterDelay(Math.max(quietTicks, recoveryTicks) + 2, () -> {
                double bound = penalty * 0.1 + ak.inaccuracy().land() + 1.0E-9;
                helper.assertTrue(state.accuracyPenalty <= bound,
                        "penalty must recover to 10%: " + state.accuracyPenalty + " > " + bound);
                assertKicks(helper, spray(player, ak, 1), ak, 1, "spray after quiet period");
                helper.assertValueEqual(state.shotsFired(), 1, "restarted spray index");
                remove(player);
                helper.succeed();
            });
        });
    }

    private static TraceResult trace(GameTestHelper helper, int lane, WeaponData weapon) {
        Vec3 muzzle = helper.absoluteVec(new Vec3(0.5, 2.5, lane + 0.5));
        return Ballistics.trace(helper.getLevel(), null, muzzle, EAST, weapon);
    }

    /** Authored rifle-class weapon with a short range; independent of the bundled or cached AK-47. */
    private static WeaponData testRifle() {
        JsonObject json = new JsonObject();
        json.addProperty("damage", 36.0);
        json.addProperty("magazine_size", 30);
        json.addProperty("cycle_time_s", 0.1);
        json.addProperty("range", TEST_RANGE_UNITS);
        json.addProperty("range_modifier", 0.98);
        json.addProperty("penetration_power", 2.0);
        json.addProperty("penetration_count", 4);
        json.addProperty("penetration_distance", 90.0);
        json.addProperty("automatic", true);
        return WeaponData.fromJson(CsmcMod.id("test_rifle"), json);
    }

    /**
     * A fence's collision shape overhangs 0.5 blocks into the cell above it. A ray through that overhang
     * (never entering the fence's own cell) must still hit the fence, measuring only the 4/16-block post.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void overhangingFenceIsHitInTheCellAbove(GameTestHelper helper) {
        BlockPos fence = new BlockPos(2, 1, LANE);
        helper.setBlock(fence, Blocks.OAK_FENCE);
        setBlockBreaking(helper, true);
        Vec3 muzzle = helper.absoluteVec(new Vec3(0.5, 2.25, LANE + 0.5));
        TraceResult result = Ballistics.trace(helper.getLevel(), null, muzzle, EAST, testRifle());
        helper.assertTrue(!result.impacts().isEmpty()
                        && result.impacts().get(0) instanceof TraceResult.BlockImpact block
                        && block.pos().equals(helper.absolutePos(fence)),
                "ray through the overhang must hit the fence, got " + result.impacts());
        TraceResult.BlockImpact hit = (TraceResult.BlockImpact) result.impacts().get(0);
        assertThickness(helper, hit, 4.0, "fence post");
        helper.assertTrue(Math.abs(hit.distance() - (2.375 - 0.5)) < 1.0E-6,
                "entry must be the post's face, got " + hit.distance());
        helper.succeed();
    }

    /** A cauldron is hollow: a ray through its basin crosses two 2/16-block walls, not a full block. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void hollowCauldronCountsOnlyItsWalls(GameTestHelper helper) {
        BlockPos cauldron = new BlockPos(2, 2, LANE);
        helper.setBlock(cauldron, Blocks.CAULDRON);
        setBlockBreaking(helper, true);
        TraceResult result = trace(helper, LANE, testRifle());
        helper.assertTrue(!result.impacts().isEmpty()
                        && result.impacts().get(0) instanceof TraceResult.BlockImpact block
                        && block.pos().equals(helper.absolutePos(cauldron)),
                "ray must hit the cauldron, got " + result.impacts());
        assertThickness(helper, (TraceResult.BlockImpact) result.impacts().get(0), 4.0, "cauldron walls");
        helper.succeed();
    }

    /**
     * Drawing another stack of the same weapon — replaced in the same slot or held in another slot —
     * restarts the spray and clears the fire penalty, like a CS deploy.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void sprayResetsWhenSwitchingSameTypeStacks(GameTestHelper helper) {
        WeaponData ak = WeaponRegistry.get(CsmcMod.id("ak47"));
        helper.assertTrue(ak != null, "csmc:ak47 must be loaded");
        ServerPlayer player = shooter(helper);
        ShooterState state = ShooterState.of(player);
        player.getInventory().setItem(0, new ItemStack(CsmcMod.AK47));
        player.getInventory().setItem(1, new ItemStack(CsmcMod.AK47));
        player.getInventory().selected = 0;

        assertKicks(helper, spray(player, ak, 3), ak, 1, "first stack");
        helper.assertValueEqual(state.shotsFired(), 3, "spray index before switch");

        player.getInventory().setItem(0, new ItemStack(CsmcMod.AK47));
        assertKicks(helper, spray(player, ak, 2), ak, 1, "replacement stack in the same slot");
        helper.assertValueEqual(state.shotsFired(), 2, "spray index after replacing the stack");

        player.getInventory().selected = 1;
        assertKicks(helper, spray(player, ak, 1), ak, 1, "same weapon in another slot");
        helper.assertValueEqual(state.shotsFired(), 1, "spray index after changing slot");
        helper.assertTrue(Math.abs(state.accuracyPenalty - ak.inaccuracy().fire()) < 1.0E-9,
                "switch must clear the fire penalty, got " + state.accuracyPenalty);
        remove(player);
        helper.succeed();
    }

    private static void assertThickness(
            GameTestHelper helper, TraceResult.BlockImpact impact, double expectedUnits, String label) {
        helper.assertTrue(Math.abs(impact.thicknessUnits() - expectedUnits) < 1.0E-4,
                label + " thickness: expected " + expectedUnits + " units, got " + impact.thicknessUnits());
    }

    private static WeaponData recoilWeapon(String name, boolean withPattern) {
        JsonObject json = new JsonObject();
        json.addProperty("damage", 30.0);
        json.addProperty("magazine_size", 30);
        json.addProperty("cycle_time_s", 0.1);
        json.addProperty("range", 2.0 * WeaponData.UNITS_PER_BLOCK);
        json.addProperty("automatic", true);
        JsonObject recoil = new JsonObject();
        recoil.addProperty("angle", 0.0);
        recoil.addProperty("angle_variance", 45.0);
        recoil.addProperty("magnitude", 40.0);
        recoil.addProperty("magnitude_variance", 10.0);
        recoil.addProperty("seed", 1234);
        if (withPattern) {
            JsonArray pattern = new JsonArray();
            pattern.add(pair(1.0, 0.0));
            pattern.add(pair(1.5, -0.5));
            pattern.add(pair(2.0, 0.75));
            recoil.add("pattern", pattern);
        }
        json.add("recoil", recoil);
        return WeaponData.fromJson(CsmcMod.id(name), json);
    }

    private static JsonArray pair(double pitch, double yaw) {
        JsonArray pair = new JsonArray();
        pair.add(pitch);
        pair.add(yaw);
        return pair;
    }

    /**
     * Fires {@code shots} real shots in this tick from a fixed start view and returns each shot's kick. The
     * mock client never echoes the kicks, so the view stays put; each kick must instead be added to the
     * server-side aim offset that later shots in the burst aim with.
     */
    private static List<WeaponData.Kick> spray(ServerPlayer player, WeaponData weapon, int shots) {
        player.setYRot(0.0F);
        player.setXRot(-60.0F);
        ShooterState state = ShooterState.of(player);
        List<WeaponData.Kick> kicks = new ArrayList<>(shots);
        for (int i = 0; i < shots; i++) {
            double pitchBefore = state.pendingPitch();
            double yawBefore = state.pendingYaw();
            WeaponData.Kick kick = Ballistics.fire(player, weapon, i + 1, weapon.magazineSize() - 1 - i);
            WeaponData.Kick offset = new WeaponData.Kick(state.pendingPitch() - pitchBefore, state.pendingYaw() - yawBefore);
            if (!near(offset, kick)) {
                throw new GameTestAssertException("aim offset must grow by the sent kick " + kick + ", grew " + offset);
            }
            if (player.getXRot() != -60.0F || player.getYRot() != 0.0F) {
                throw new GameTestAssertException("recoil must not teleport the view server-side");
            }
            kicks.add(kick);
        }
        return kicks;
    }

    private static void assertKicks(
            GameTestHelper helper, List<WeaponData.Kick> kicks, WeaponData weapon, int firstShot, String label) {
        for (int i = 0; i < kicks.size(); i++) {
            WeaponData.Kick expected = weapon.recoil().kick(firstShot + i);
            helper.assertTrue(near(kicks.get(i), expected),
                    label + " shot " + (firstShot + i) + ": expected " + expected + ", got " + kicks.get(i));
        }
    }

    private static void assertSameKicks(
            GameTestHelper helper, List<WeaponData.Kick> actual, List<WeaponData.Kick> expected, String label) {
        helper.assertValueEqual(actual.size(), expected.size(), label + " size");
        for (int i = 0; i < actual.size(); i++) {
            helper.assertTrue(near(actual.get(i), expected.get(i)),
                    label + " shot " + (i + 1) + ": expected " + expected.get(i) + ", got " + actual.get(i));
        }
    }

    private static boolean near(WeaponData.Kick a, WeaponData.Kick b) {
        return Math.abs(a.pitch() - b.pitch()) < KICK_TOLERANCE_DEG && Math.abs(a.yaw() - b.yaw()) < KICK_TOLERANCE_DEG;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer shooter(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(4.5, 2.0, 4.5));
        player.teleportTo(position.x, position.y, position.z);
        return player;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }

    private static void setBlockBreaking(GameTestHelper helper, boolean enabled) {
        helper.getLevel().getGameRules().getRule(BlockBreakPolicy.BLOCK_BREAKING_RULE)
                .set(enabled, helper.getLevel().getServer());
    }

    private static TraceResult.EntityImpact entityImpact(GameTestHelper helper, TraceResult.Impact impact, Zombie zombie) {
        if (impact instanceof TraceResult.EntityImpact hit && hit.entity() == zombie && hit.hurt()) {
            return hit;
        }
        throw new GameTestAssertException(
                "expected a damaging hit on " + zombie + ", got " + impact);
    }

    private static void assertBlockImpact(
            GameTestHelper helper, TraceResult.Impact impact, BlockPos relative, TraceResult.BlockOutcome outcome) {
        helper.assertTrue(impact instanceof TraceResult.BlockImpact block
                        && block.pos().equals(helper.absolutePos(relative)) && block.outcome() == outcome,
                "expected " + outcome + " at " + relative + ", got " + impact);
    }
}
