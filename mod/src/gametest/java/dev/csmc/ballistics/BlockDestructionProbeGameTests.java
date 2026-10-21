package dev.csmc.ballistics;

import com.google.gson.JsonObject;
import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Before/after probe that only uses the long-standing trace API: repeated rifle-class hits must eventually
 * destroy ordinary stone and soil (shared cumulative damage), while bedrock stays intact and stops every shot.
 */
public final class BlockDestructionProbeGameTests implements FabricGameTest {
    private static final Vec3 EAST = new Vec3(1.0, 0.0, 0.0);
    private static final int SHOTS = 40;

    /** Own batch: the gamerule is global, so it is set and restored without racing other tests. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_block_destruction_probe")
    public void repeatedRifleHitsBreakStoneAndSoilButNotBedrock(GameTestHelper helper) {
        BlockPos stone = new BlockPos(2, 2, 1);
        BlockPos dirt = new BlockPos(2, 2, 3);
        BlockPos bedrock = new BlockPos(2, 2, 5);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(dirt, Blocks.DIRT);
        helper.setBlock(bedrock, Blocks.BEDROCK);
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(BlockBreakPolicy.BLOCK_BREAKING_RULE);
        boolean previous = rule.get();
        rule.set(true, helper.getLevel().getServer());
        try {
            WeaponData rifle = rifle();
            int stoneShots = shotsToClear(helper, stone, 1, rifle);
            int dirtShots = shotsToClear(helper, dirt, 3, rifle);
            for (int i = 0; i < SHOTS; i++) {
                TraceResult result = trace(helper, 5, rifle);
                helper.assertValueEqual(result.stopReason(), TraceResult.StopReason.BLOCK, "bedrock stop reason");
            }
            helper.assertTrue(stoneShots > 1, "stone must survive the first rifle hit, broke after " + stoneShots);
            helper.assertTrue(dirtShots > 1, "dirt must survive the first rifle hit, broke after " + dirtShots);
            helper.assertTrue(stoneShots > dirtShots,
                    "stone (" + stoneShots + " hits) must need more hits than dirt (" + dirtShots + ")");
            helper.assertBlockPresent(Blocks.BEDROCK, bedrock);
        } finally {
            rule.set(previous, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    /** Fires along {@code lane} until {@code pos} is air; fails if it survives {@link #SHOTS} hits. */
    private static int shotsToClear(GameTestHelper helper, BlockPos pos, int lane, WeaponData rifle) {
        for (int shot = 1; shot <= SHOTS; shot++) {
            trace(helper, lane, rifle);
            if (helper.getLevel().getBlockState(helper.absolutePos(pos)).isAir()) {
                return shot;
            }
        }
        helper.fail("block at " + pos + " survived " + SHOTS + " rifle hits: "
                + helper.getLevel().getBlockState(helper.absolutePos(pos)));
        return SHOTS;
    }

    private static TraceResult trace(GameTestHelper helper, int lane, WeaponData weapon) {
        Vec3 muzzle = helper.absoluteVec(new Vec3(0.5, 2.5, lane + 0.5));
        return Ballistics.trace(helper.getLevel(), null, muzzle, EAST, weapon);
    }

    /** AK-like authored rifle (36 CS damage per bullet); independent of the cached weapon data. */
    private static WeaponData rifle() {
        JsonObject json = new JsonObject();
        json.addProperty("damage", 36.0);
        json.addProperty("magazine_size", 30);
        json.addProperty("cycle_time_s", 0.1);
        json.addProperty("range", 7.0 * WeaponData.UNITS_PER_BLOCK);
        json.addProperty("range_modifier", 0.98);
        json.addProperty("penetration_power", 2.0);
        json.addProperty("penetration_count", 4);
        json.addProperty("penetration_distance", 90.0);
        json.addProperty("automatic", true);
        return WeaponData.fromJson(CsmcMod.id("test_probe_rifle"), json);
    }
}
