package dev.csmc.ballistics;

import com.google.gson.JsonObject;
import dev.csmc.CsmcMod;
import dev.csmc.data.MaterialTable;
import dev.csmc.data.WeaponData;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Material-aware block damage in the real world: shared cumulative damage and cracks, expiry, immune blocks,
 * protection/gamerule denial and destruction failure. Thresholds are never pinned; tests compare world
 * behavior (hits needed, block presence, downstream hits) between materials and weapons. Lanes run along +X
 * at relative y = 2 inside the 8x8x8 empty structure, like {@link BallisticsGameTests}.
 */
public final class BlockDamageGameTests implements FabricGameTest {
    private static final Vec3 EAST = new Vec3(1.0, 0.0, 0.0);
    private static final int MAX_SHOTS = 200;
    private static final double EPSILON = 1.0E-6;

    /** Absolute positions a claim-like Fabric listener vetoes; empty outside the veto test. */
    private static final Set<BlockPos> VETOED = new HashSet<>();
    /** Absolute positions a listener replaces (once) while approving the break; empty outside that test. */
    private static final Map<BlockPos, BlockState> REPLACE_ON_APPROVAL = new HashMap<>();
    private static int vetoCancellations;
    /** Test hook: AFTER for a key position breaks the mapped unrelated position with ordinary drops (one-shot). */
    private static final Map<BlockPos, BlockPos> AFTER_BREAKS_WITH_DROPS = new HashMap<>();
    /** Test hook: removing the block entity at a key position runs the mapped action (one-shot). */
    private static final Map<BlockPos, Runnable> ON_BLOCK_ENTITY_REMOVED = new HashMap<>();
    /** Test hook: AFTER deliveries per watched absolute position; counts every call, so duplicates show. */
    private static final Map<BlockPos, Integer> AFTER_COUNTS = new HashMap<>();
    /** Test hook: AFTER for a key position runs the mapped action on every delivery, after counting it. */
    private static final Map<BlockPos, Runnable> AFTER_ACTIONS = new HashMap<>();

    /** Thrown by test hooks to simulate a failing mod listener. */
    private static final class HookFailure extends RuntimeException {
        HookFailure(String message) {
            super(message);
        }
    }

    static {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            BlockState replacement = REPLACE_ON_APPROVAL.remove(pos);
            if (replacement != null) {
                level.setBlockAndUpdate(pos, replacement);
            }
            return !VETOED.contains(pos);
        });
        PlayerBlockBreakEvents.CANCELED.register((level, player, pos, state, blockEntity) -> {
            if (VETOED.contains(pos)) {
                vetoCancellations++;
            }
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            AFTER_COUNTS.computeIfPresent(pos, (key, count) -> count + 1);
            Runnable action = AFTER_ACTIONS.get(pos);
            if (action != null) {
                action.run();
            }
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            BlockPos target = AFTER_BREAKS_WITH_DROPS.remove(pos);
            if (target != null) {
                level.destroyBlock(target, true);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            Runnable action = ON_BLOCK_ENTITY_REMOVED.remove(blockEntity.getBlockPos());
            if (action != null) {
                action.run();
            }
        });
    }

    /**
     * AFTER callbacks run without CS drop suppression, both for a top-level CS break and for a CS break nested
     * inside another one's cascade (stone breaks, the banner on it loses support, its removal triggers a
     * second CS break): a listener's own unrelated vanilla break drops items.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void afterCallbacksRunWithoutDropSuppression(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos topStone = new BlockPos(2, 2, 1);
        BlockPos topDirt = new BlockPos(6, 1, 0);
        BlockPos nestedStone = new BlockPos(2, 2, 4);
        BlockPos banner = nestedStone.above();
        BlockPos nestedGlass = new BlockPos(5, 6, 6);
        BlockPos nestedDirt = new BlockPos(1, 1, 7);
        helper.setBlock(topStone, Blocks.STONE);
        helper.setBlock(topDirt, Blocks.DIRT);
        helper.setBlock(nestedStone, Blocks.STONE);
        helper.setBlock(banner, Blocks.WHITE_BANNER);
        helper.setBlock(nestedGlass, Blocks.GLASS);
        helper.setBlock(nestedDirt, Blocks.DIRT);
        WeaponData cannon = weapon("test_block_cannon", 1000.0);
        ServerPlayer shooter = shooter(helper);
        BlockPos absTopStone = helper.absolutePos(topStone);
        BlockPos absBanner = helper.absolutePos(banner);
        BlockPos absGlass = helper.absolutePos(nestedGlass);
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            AFTER_BREAKS_WITH_DROPS.put(absTopStone, helper.absolutePos(topDirt));
            AFTER_BREAKS_WITH_DROPS.put(absGlass, helper.absolutePos(nestedDirt));
            ON_BLOCK_ENTITY_REMOVED.put(absBanner, () -> helper.assertValueEqual(
                    BlockDamage.apply(level, shooter, absGlass, 1000.0).outcome(), BlockDamage.Outcome.BROKEN,
                    "nested CS break"));

            helper.assertValueEqual(firstBlockImpact(helper, trace(helper, shooter, 1, cannon), topStone).outcome(),
                    TraceResult.BlockOutcome.BROKEN, "top-level stone");
            helper.assertBlockPresent(Blocks.AIR, topDirt);
            helper.assertEntityPresent(EntityType.ITEM, topDirt, 2.0);

            helper.assertValueEqual(firstBlockImpact(helper, trace(helper, shooter, 4, cannon), nestedStone).outcome(),
                    TraceResult.BlockOutcome.BROKEN, "outer stone");
            helper.assertBlockPresent(Blocks.AIR, banner);
            helper.assertBlockPresent(Blocks.AIR, nestedGlass);
            helper.assertBlockPresent(Blocks.AIR, nestedDirt);
            helper.assertEntityPresent(EntityType.ITEM, nestedDirt, 2.0);
        } finally {
            AFTER_BREAKS_WITH_DROPS.remove(absTopStone);
            AFTER_BREAKS_WITH_DROPS.remove(absGlass);
            ON_BLOCK_ENTITY_REMOVED.remove(absBanner);
            remove(shooter);
        }
        helper.killAllEntitiesOfClass(ItemEntity.class);
        helper.succeed();
    }

    /**
     * Outer CS break A throws (a block-entity removal hook in its cascade fails) after a nested CS break B
     * succeeded: B's AFTER is still delivered exactly once and A's exception still reaches the caller.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void nestedAfterIsDeliveredWhenOuterBreakThrows(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos stone = new BlockPos(2, 2, 1);
        BlockPos banner = stone.above();
        BlockPos glass = new BlockPos(5, 6, 6);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(banner, Blocks.WHITE_BANNER);
        helper.setBlock(glass, Blocks.GLASS);
        ServerPlayer shooter = shooter(helper);
        BlockPos absBanner = helper.absolutePos(banner);
        BlockPos absGlass = helper.absolutePos(glass);
        boolean thrown = false;
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            AFTER_COUNTS.put(absGlass, 0);
            ON_BLOCK_ENTITY_REMOVED.put(absBanner, () -> {
                helper.assertValueEqual(BlockDamage.apply(level, shooter, absGlass, 1000.0).outcome(),
                        BlockDamage.Outcome.BROKEN, "nested CS break");
                throw new HookFailure("outer cascade failed");
            });
            try {
                trace(helper, shooter, 1, weapon("test_block_cannon", 1000.0));
            } catch (RuntimeException e) {
                thrown = causedByHook(e);
                if (!thrown) {
                    throw e;
                }
            }
            helper.assertTrue(thrown, "the outer break's failure must reach the caller");
            helper.assertBlockPresent(Blocks.AIR, glass);
            helper.assertValueEqual(AFTER_COUNTS.get(absGlass), 1, "AFTER deliveries for the nested break");
        } finally {
            AFTER_COUNTS.remove(absGlass);
            ON_BLOCK_ENTITY_REMOVED.remove(absBanner);
            remove(shooter);
        }
        helper.succeed();
    }

    /**
     * A listener that throws for the first removed position of an assembly does not stop the delivery for the
     * second; each is delivered exactly once and the listener's exception still reaches the caller.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void throwingAfterListenerDoesNotDropLaterCallbacks(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos upper = placeDoor(helper, 3);
        BlockPos absUpper = helper.absolutePos(upper);
        BlockPos absLower = helper.absolutePos(upper.below());
        ServerPlayer shooter = shooter(helper);
        boolean thrown = false;
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            AFTER_COUNTS.put(absUpper, 0);
            AFTER_COUNTS.put(absLower, 0);
            AFTER_ACTIONS.put(absLower, () -> {
                throw new HookFailure("listener failed");
            });
            try {
                trace(helper, shooter, 3, weapon("test_block_cannon", 1000.0));
            } catch (RuntimeException e) {
                thrown = causedByHook(e);
                if (!thrown) {
                    throw e;
                }
            }
            helper.assertTrue(thrown, "the listener's failure must reach the caller");
            helper.assertBlockPresent(Blocks.AIR, upper);
            helper.assertBlockPresent(Blocks.AIR, upper.below());
            helper.assertValueEqual(AFTER_COUNTS.get(absLower), 1, "AFTER deliveries for the lower half");
            helper.assertValueEqual(AFTER_COUNTS.get(absUpper), 1, "AFTER deliveries for the upper half");
        } finally {
            AFTER_COUNTS.remove(absUpper);
            AFTER_COUNTS.remove(absLower);
            AFTER_ACTIONS.remove(absLower);
            remove(shooter);
        }
        helper.succeed();
    }

    /** An AFTER listener that itself causes a CS break: every removal is delivered exactly once. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void csBreakFromAfterListenerIsDeliveredOnce(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos stone = new BlockPos(2, 2, 5);
        BlockPos glass = new BlockPos(5, 6, 2);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(glass, Blocks.GLASS);
        ServerPlayer shooter = shooter(helper);
        BlockPos absStone = helper.absolutePos(stone);
        BlockPos absGlass = helper.absolutePos(glass);
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            AFTER_COUNTS.put(absStone, 0);
            AFTER_COUNTS.put(absGlass, 0);
            AFTER_ACTIONS.put(absStone, () -> helper.assertValueEqual(
                    BlockDamage.apply(level, shooter, absGlass, 1000.0).outcome(), BlockDamage.Outcome.BROKEN,
                    "CS break from the AFTER listener"));
            helper.assertValueEqual(firstBlockImpact(helper, trace(helper, shooter, 5, weapon("test_block_cannon", 1000.0)),
                    stone).outcome(), TraceResult.BlockOutcome.BROKEN, "stone");
            helper.assertBlockPresent(Blocks.AIR, glass);
            helper.assertValueEqual(AFTER_COUNTS.get(absStone), 1, "AFTER deliveries for the stone");
            helper.assertValueEqual(AFTER_COUNTS.get(absGlass), 1, "AFTER deliveries for the glass");
            helper.assertFalse(BlockBreakPolicy.scopeBound(), "CS break scope must be released after the outermost break");
        } finally {
            AFTER_COUNTS.remove(absStone);
            AFTER_COUNTS.remove(absGlass);
            AFTER_ACTIONS.remove(absStone);
            remove(shooter);
        }
        helper.succeed();
    }

    /**
     * The hit block is already gone when a hook in its removal cascade throws: its AFTER is still delivered
     * exactly once, the failure reaches the caller, and the thread's CS break scope is released.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void committedRemovalIsDeliveredWhenItsCascadeThrows(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos stone = new BlockPos(2, 2, 1);
        BlockPos banner = stone.above();
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(banner, Blocks.WHITE_BANNER);
        ServerPlayer shooter = shooter(helper);
        BlockPos absStone = helper.absolutePos(stone);
        BlockPos absBanner = helper.absolutePos(banner);
        boolean thrown = false;
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            AFTER_COUNTS.put(absStone, 0);
            ON_BLOCK_ENTITY_REMOVED.put(absBanner, () -> {
                throw new HookFailure("cascade failed");
            });
            try {
                trace(helper, shooter, 1, weapon("test_block_cannon", 1000.0));
            } catch (RuntimeException e) {
                thrown = causedByHook(e);
                if (!thrown) {
                    throw e;
                }
            }
            helper.assertTrue(thrown, "the cascade's failure must reach the caller");
            helper.assertBlockPresent(Blocks.AIR, stone);
            helper.assertValueEqual(AFTER_COUNTS.get(absStone), 1, "AFTER deliveries for the removed stone");
            helper.assertFalse(BlockBreakPolicy.scopeBound(), "CS break scope must be released after a failure");
        } finally {
            AFTER_COUNTS.remove(absStone);
            ON_BLOCK_ENTITY_REMOVED.remove(absBanner);
            remove(shooter);
        }
        helper.succeed();
    }

    /**
     * Two AFTER listeners throw the same exception instance: the caller gets exactly that instance and every
     * queued callback (two nested glass breaks, then the outer stone) is still delivered once.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void sameExceptionFromListenersKeepsIdentityAndDelivery(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos stone = new BlockPos(2, 2, 3);
        BlockPos banner = stone.above();
        BlockPos firstGlass = new BlockPos(5, 6, 5);
        BlockPos secondGlass = new BlockPos(6, 6, 5);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(banner, Blocks.WHITE_BANNER);
        helper.setBlock(firstGlass, Blocks.GLASS);
        helper.setBlock(secondGlass, Blocks.GLASS);
        ServerPlayer shooter = shooter(helper);
        BlockPos absStone = helper.absolutePos(stone);
        BlockPos absBanner = helper.absolutePos(banner);
        BlockPos absFirst = helper.absolutePos(firstGlass);
        BlockPos absSecond = helper.absolutePos(secondGlass);
        HookFailure same = new HookFailure("same instance");
        RuntimeException caught = null;
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            for (BlockPos pos : List.of(absStone, absFirst, absSecond)) {
                AFTER_COUNTS.put(pos, 0);
            }
            AFTER_ACTIONS.put(absFirst, () -> {
                throw same;
            });
            AFTER_ACTIONS.put(absSecond, () -> {
                throw same;
            });
            ON_BLOCK_ENTITY_REMOVED.put(absBanner, () -> {
                for (BlockPos glass : List.of(absFirst, absSecond)) {
                    helper.assertValueEqual(BlockDamage.apply(level, shooter, glass, 1000.0).outcome(),
                            BlockDamage.Outcome.BROKEN, "nested CS break");
                }
            });
            try {
                trace(helper, shooter, 3, weapon("test_block_cannon", 1000.0));
            } catch (RuntimeException e) {
                caught = e;
            }
            helper.assertTrue(caught == same, "the listeners' own exception instance must reach the caller, got " + caught);
            for (BlockPos pos : List.of(absStone, absFirst, absSecond)) {
                helper.assertValueEqual(AFTER_COUNTS.get(pos), 1, "AFTER deliveries at " + pos);
            }
            helper.assertFalse(BlockBreakPolicy.scopeBound(), "CS break scope must be released");
        } finally {
            for (BlockPos pos : List.of(absStone, absFirst, absSecond)) {
                AFTER_COUNTS.remove(pos);
                AFTER_ACTIONS.remove(pos);
            }
            ON_BLOCK_ENTITY_REMOVED.remove(absBanner);
            remove(shooter);
        }
        helper.succeed();
    }

    private static boolean causedByHook(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof HookFailure) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wood and stone take damage below their threshold (block stays, cracks tracked), break on the hit that
     * crosses it (reported BROKEN exactly once, cracks cleared), and stone needs more hits than wood.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void woodAndStoneAccumulateUntilThreshold(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos plank = new BlockPos(2, 2, 1);
        BlockPos stone = new BlockPos(2, 2, 3);
        helper.setBlock(plank, Blocks.OAK_PLANKS);
        helper.setBlock(stone, Blocks.STONE);
        WeaponData rifle = weapon("test_block_rifle", 36.0);

        int woodHits = hitsToBreak(helper, plank, 1, rifle, Blocks.OAK_PLANKS);
        int stoneHits = hitsToBreak(helper, stone, 3, rifle, Blocks.STONE);

        helper.assertTrue(stoneHits > woodHits,
                "stone (" + stoneHits + " hits) must need more rifle hits than wood (" + woodHits + ")");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void strongerBulletNeedsFewerHits(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos rifleStone = new BlockPos(2, 2, 1);
        BlockPos heavyStone = new BlockPos(2, 2, 3);
        helper.setBlock(rifleStone, Blocks.STONE);
        helper.setBlock(heavyStone, Blocks.STONE);

        int rifleHits = hitsToBreak(helper, rifleStone, 1, weapon("test_block_rifle", 36.0), Blocks.STONE);
        int heavyHits = hitsToBreak(helper, heavyStone, 3, weapon("test_block_heavy", 115.0), Blocks.STONE);

        helper.assertTrue(heavyHits < rifleHits,
                "a 115-damage bullet (" + heavyHits + " hits) must need fewer hits than a 36-damage one (" + rifleHits + ")");
        helper.succeed();
    }

    /** Damage is keyed by exact position, block state and dimension; a changed state starts from zero. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void damageIsIsolatedByPositionStateAndDimension(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos logPos = new BlockPos(2, 2, 1);
        BlockPos neighbour = new BlockPos(2, 2, 3);
        BlockState logX = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState logZ = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        helper.setBlock(logPos, logX);
        helper.setBlock(neighbour, logX);
        WeaponData rifle = weapon("test_block_rifle", 36.0);

        double first = singleBlockHit(helper, 1, rifle, logPos, TraceResult.BlockOutcome.DAMAGED);
        BlockPos absLog = helper.absolutePos(logPos);
        helper.assertTrue(Math.abs(BlockDamageTracker.damage(level, absLog, logX) - first) < EPSILON,
                "tracker must hold the hit's damage");
        helper.assertValueEqual(BlockDamageTracker.damage(level, helper.absolutePos(neighbour), logX), 0.0,
                "an untouched neighbour has no damage");
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "nether level must exist");
        helper.assertFalse(BlockDamageTracker.tracked(nether, absLog), "same coordinates in another dimension are untouched");

        helper.setBlock(logPos, logZ);
        helper.assertValueEqual(BlockDamageTracker.damage(level, absLog, logZ), 0.0, "a changed state has no damage");
        helper.runAfterDelay(2, () -> {
            helper.assertFalse(BlockDamageTracker.tracked(level, absLog), "tick must drop damage of a changed state");
            helper.setBlock(logPos, logX);
            double again = singleBlockHit(helper, 1, rifle, logPos, TraceResult.BlockOutcome.DAMAGED);
            helper.assertTrue(Math.abs(BlockDamageTracker.damage(level, absLog, logX) - again) < EPSILON,
                    "restoring the old state must not resurrect old damage");
            helper.succeed();
        });
    }

    /** Each hit refreshes the quiet timer; after FORGET_AFTER_TICKS without hits the damage and cracks are gone. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 480)
    public void damageExpiresAfterQuietPeriodAndHitsRefreshIt(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos stone = new BlockPos(2, 2, 1);
        helper.setBlock(stone, Blocks.STONE);
        BlockPos abs = helper.absolutePos(stone);
        BlockState state = Blocks.STONE.defaultBlockState();
        WeaponData rifle = weapon("test_block_rifle", 36.0);
        int nearlyForgotten = BlockDamageTracker.FORGET_AFTER_TICKS - 10;

        double first = singleBlockHit(helper, 1, rifle, stone, TraceResult.BlockOutcome.DAMAGED);
        helper.runAfterDelay(nearlyForgotten, () -> {
            helper.assertTrue(Math.abs(BlockDamageTracker.damage(level, abs, state) - first) < EPSILON,
                    "damage must survive " + nearlyForgotten + " quiet ticks");
            double second = singleBlockHit(helper, 1, rifle, stone, TraceResult.BlockOutcome.DAMAGED);
            helper.runAfterDelay(nearlyForgotten, () -> {
                helper.assertTrue(Math.abs(BlockDamageTracker.damage(level, abs, state) - (first + second)) < EPSILON,
                        "the second hit must refresh the timer and keep both hits");
                helper.runAfterDelay(30, () -> {
                    helper.assertFalse(BlockDamageTracker.tracked(level, abs), "damage must expire after the quiet period");
                    helper.assertBlockPresent(Blocks.STONE, stone);
                    helper.succeed();
                });
            });
        });
    }

    /** Bedrock (hardness < 0) and an explicit stopper never take damage or let even huge bullets through. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void immuneBlocksNeverChange(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos bedrock = new BlockPos(2, 2, 1);
        BlockPos netherite = new BlockPos(2, 2, 3);
        helper.setBlock(bedrock, Blocks.BEDROCK);
        helper.setBlock(netherite, Blocks.NETHERITE_BLOCK);
        WeaponData cannon = weapon("test_block_cannon", 1000.0);

        for (int shot = 0; shot < 20; shot++) {
            for (int lane : new int[] {1, 3}) {
                TraceResult result = trace(helper, null, lane, cannon);
                helper.assertValueEqual(result.stopReason(), TraceResult.StopReason.BLOCK, "immune stop reason");
            }
        }
        for (BlockPos pos : List.of(bedrock, netherite)) {
            BlockDamage.Result direct = BlockDamage.apply(level, null, helper.absolutePos(pos), 1.0E6);
            helper.assertValueEqual(direct.outcome(), BlockDamage.Outcome.INTACT, "direct damage outcome");
            helper.assertValueEqual(direct.appliedDamage(), 0.0, "direct applied damage");
            helper.assertFalse(BlockDamageTracker.tracked(level, helper.absolutePos(pos)), "immune block cracks");
        }
        helper.assertBlockPresent(Blocks.BEDROCK, bedrock);
        helper.assertBlockPresent(Blocks.NETHERITE_BLOCK, netherite);
        helper.succeed();
    }

    /**
     * The shot that breaks a block still pays for the block's original material: same remaining damage and
     * penetration count as a shot that only damages an identical fresh plank.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakingShotStillPaysPenetrationLoss(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos primed = new BlockPos(2, 2, 1);
        BlockPos fresh = new BlockPos(2, 2, 3);
        helper.setBlock(primed, Blocks.OAK_PLANKS);
        helper.setBlock(fresh, Blocks.OAK_PLANKS);
        WeaponData rifle = weapon("test_block_rifle", 36.0);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();

        // Prime the plank until the next identical hit must break it (every hit on this lane carries the same damage).
        double threshold = MaterialTable.lookup(planks).breakDamage();
        double hit = singleBlockHit(helper, 1, rifle, primed, TraceResult.BlockOutcome.DAMAGED);
        while (BlockDamageTracker.damage(helper.getLevel(), helper.absolutePos(primed), planks) + hit < threshold) {
            singleBlockHit(helper, 1, rifle, primed, TraceResult.BlockOutcome.DAMAGED);
        }
        TraceResult breaking = trace(helper, null, 1, rifle);
        TraceResult damaging = trace(helper, null, 3, rifle);
        TraceResult open = trace(helper, null, 5, rifle);

        assertOnlyBlockImpact(helper, breaking, primed, TraceResult.BlockOutcome.BROKEN);
        assertOnlyBlockImpact(helper, damaging, fresh, TraceResult.BlockOutcome.DAMAGED);
        helper.assertBlockPresent(Blocks.AIR, primed);
        helper.assertTrue(Math.abs(breaking.remainingDamage() - damaging.remainingDamage()) < EPSILON,
                "breaking shot " + breaking.remainingDamage() + " must lose as much as a damaging shot "
                        + damaging.remainingDamage());
        helper.assertTrue(breaking.remainingDamage() < open.remainingDamage() - 1.0,
                "breaking shot must lose damage to the plank: " + breaking.remainingDamage() + " vs open " + open.remainingDamage());
        helper.assertValueEqual(breaking.remainingPenetrations(), rifle.penetrationCount() - 1, "penetrations left");
        helper.succeed();
    }

    /**
     * A real shooter denied by game mode or a claim veto neither damages nor shoots through cover; a
     * permitted survival shooter does both. Denied cover shields the zombie behind it.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void protectedCoverIsNotDamagedOrShotThrough(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos adventureCover = new BlockPos(2, 2, 1);
        BlockPos vetoedCover = new BlockPos(2, 2, 3);
        BlockPos allowedCover = new BlockPos(2, 2, 5);
        for (BlockPos cover : List.of(adventureCover, vetoedCover, allowedCover)) {
            helper.setBlock(cover, Blocks.OAK_PLANKS);
        }
        Zombie behindAdventure = zombieBehind(helper, 1);
        Zombie behindVeto = zombieBehind(helper, 3);
        Zombie behindAllowed = zombieBehind(helper, 5);
        float adventureHealth = behindAdventure.getHealth();
        float vetoHealth = behindVeto.getHealth();
        float allowedHealth = behindAllowed.getHealth();
        ServerPlayer shooter = shooter(helper);
        WeaponData rifle = weapon("test_block_rifle", 36.0);
        BlockPos absVeto = helper.absolutePos(vetoedCover);
        try {
            shooter.setGameMode(GameType.ADVENTURE);
            assertForbidden(helper, trace(helper, shooter, 1, rifle), adventureCover, Blocks.OAK_PLANKS);
            helper.assertValueEqual(BlockDamage.apply(level, shooter, helper.absolutePos(adventureCover), 1000.0).outcome(),
                    BlockDamage.Outcome.DENIED, "adventure direct damage");

            shooter.setGameMode(GameType.SURVIVAL);
            VETOED.add(absVeto);
            int cancellationsBefore = vetoCancellations;
            assertForbidden(helper, trace(helper, shooter, 3, rifle), vetoedCover, Blocks.OAK_PLANKS);
            helper.assertTrue(vetoCancellations > cancellationsBefore, "a veto must fire CANCELED");
            helper.assertValueEqual(BlockDamage.apply(level, shooter, absVeto, 1000.0).outcome(),
                    BlockDamage.Outcome.DENIED, "vetoed direct damage");

            TraceResult allowed = trace(helper, shooter, 5, rifle);
            helper.assertTrue(allowed.impacts().size() >= 2
                            && allowed.impacts().get(0) instanceof TraceResult.BlockImpact block
                            && block.outcome() == TraceResult.BlockOutcome.DAMAGED
                            && allowed.impacts().get(1) instanceof TraceResult.EntityImpact hit
                            && hit.entity() == behindAllowed,
                    "a permitted shooter must damage the plank and hit the zombie, got " + allowed.impacts());
        } finally {
            VETOED.remove(absVeto);
            remove(shooter);
        }
        helper.assertValueEqual(behindAdventure.getHealth(), adventureHealth, "zombie behind adventure-protected cover");
        helper.assertValueEqual(behindVeto.getHealth(), vetoHealth, "zombie behind vetoed cover");
        helper.assertTrue(behindAllowed.getHealth() < allowedHealth, "zombie behind permitted cover must be hurt");
        for (BlockPos cover : List.of(adventureCover, vetoedCover)) {
            helper.assertFalse(BlockDamageTracker.tracked(level, helper.absolutePos(cover)), "denied cover cracks");
        }
        behindAdventure.discard();
        behindVeto.discard();
        behindAllowed.discard();
        helper.succeed();
    }

    /** Gamerule off: no block takes damage and bullets stop at the first solid block (no wallbangs). */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_block_damage_rule_off")
    public void gameruleOffStopsBulletsWithoutDamage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean previous = level.getGameRules().getBoolean(BlockBreakPolicy.BLOCK_BREAKING_RULE);
        BlockPos plank = new BlockPos(2, 2, 1);
        BlockPos stone = new BlockPos(2, 2, 3);
        helper.setBlock(plank, Blocks.OAK_PLANKS);
        helper.setBlock(stone, Blocks.STONE);
        Zombie behindPlank = zombieBehind(helper, 1);
        Zombie behindStone = zombieBehind(helper, 3);
        float plankHealth = behindPlank.getHealth();
        float stoneHealth = behindStone.getHealth();
        setBlockBreaking(helper, false);
        try {
            WeaponData heavy = weapon("test_block_heavy", 115.0);
            for (int shot = 0; shot < 5; shot++) {
                assertForbidden(helper, trace(helper, null, 1, heavy), plank, Blocks.OAK_PLANKS);
                assertForbidden(helper, trace(helper, null, 3, heavy), stone, Blocks.STONE);
            }
            helper.assertValueEqual(BlockDamage.apply(level, null, helper.absolutePos(plank), 1000.0).outcome(),
                    BlockDamage.Outcome.DENIED, "direct damage with the gamerule off");
        } finally {
            setBlockBreaking(helper, previous);
        }
        helper.assertValueEqual(behindPlank.getHealth(), plankHealth, "zombie behind plank");
        helper.assertValueEqual(behindStone.getHealth(), stoneHealth, "zombie behind stone");
        helper.assertFalse(BlockDamageTracker.tracked(level, helper.absolutePos(plank)), "plank cracks");
        helper.assertFalse(BlockDamageTracker.tracked(level, helper.absolutePos(stone)), "stone cracks");
        behindPlank.discard();
        behindStone.discard();
        helper.succeed();
    }

    /**
     * A protection callback that approves the break but replaces the block meanwhile (here: removes a pane,
     * swaps a primed plank for bedrock) must not be reported as SHATTERED/BROKEN, must not have its
     * replacement overwritten and must still shield the target behind it. Destroying an already-replaced
     * block reports the real failure.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void blockReplacedDuringApprovalIsNotReportedOrPassed(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos pane = new BlockPos(2, 2, 1);
        BlockPos plank = new BlockPos(2, 2, 3);
        helper.setBlock(pane, Blocks.GLASS_PANE);
        helper.setBlock(plank, Blocks.OAK_PLANKS);
        Zombie behindPane = zombieBehind(helper, 1);
        Zombie behindPlank = zombieBehind(helper, 3);
        WeaponData rifle = weapon("test_block_rifle", 36.0);
        BlockPos absPane = helper.absolutePos(pane);
        BlockPos absPlank = helper.absolutePos(plank);
        BlockState paneState = level.getBlockState(absPane);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        // Prime the plank so the next hit would break it if the approval were trusted.
        double hit = singleBlockHit(helper, 3, rifle, plank, TraceResult.BlockOutcome.DAMAGED);
        while (BlockDamageTracker.damage(level, absPlank, planks) + hit < MaterialTable.lookup(planks).breakDamage()) {
            singleBlockHit(helper, 3, rifle, plank, TraceResult.BlockOutcome.DAMAGED);
        }
        float paneHealth = behindPane.getHealth();
        float plankHealth = behindPlank.getHealth();
        ServerPlayer shooter = shooter(helper);
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            REPLACE_ON_APPROVAL.put(absPane, Blocks.AIR.defaultBlockState());
            REPLACE_ON_APPROVAL.put(absPlank, Blocks.BEDROCK.defaultBlockState());

            assertForbidden(helper, trace(helper, shooter, 1, rifle), pane, Blocks.AIR);
            assertForbidden(helper, trace(helper, shooter, 3, rifle), plank, Blocks.BEDROCK);
            helper.assertFalse(BlockBreakPolicy.destroy(level, shooter, absPane, paneState),
                    "destroying an already removed pane must report failure");
            helper.assertFalse(BlockBreakPolicy.destroy(level, shooter, absPlank, planks),
                    "destroying a plank replaced by bedrock must report failure");
            helper.assertBlockPresent(Blocks.BEDROCK, plank);
        } finally {
            REPLACE_ON_APPROVAL.remove(absPane);
            REPLACE_ON_APPROVAL.remove(absPlank);
            remove(shooter);
        }
        helper.assertValueEqual(behindPane.getHealth(), paneHealth, "zombie behind the replaced pane");
        helper.assertValueEqual(behindPlank.getHealth(), plankHealth, "zombie behind the replaced plank");
        behindPane.discard();
        behindPlank.discard();
        helper.succeed();
    }

    /**
     * Breaking one block of a two-block assembly removes the whole assembly without any item drops: a door hit
     * in its upper half (vanilla would otherwise pop the lower half with drops) and a piston hit in its head
     * (vanilla's head removal destroys the base with drops).
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void assembliesBreakWholeWithoutLoot(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos doorUpper = placeDoor(helper, 1);
        BlockPos pistonHead = placeExtendedPiston(helper, 3);
        WeaponData cannon = weapon("test_block_cannon", 1000.0);

        TraceResult door = trace(helper, null, 1, cannon);
        TraceResult piston = trace(helper, null, 3, cannon);

        helper.assertValueEqual(firstBlockImpact(helper, door, doorUpper).outcome(), TraceResult.BlockOutcome.BROKEN,
                "door upper half");
        helper.assertValueEqual(firstBlockImpact(helper, piston, pistonHead).outcome(), TraceResult.BlockOutcome.BROKEN,
                "piston head");
        helper.assertBlockPresent(Blocks.AIR, doorUpper);
        helper.assertBlockPresent(Blocks.AIR, doorUpper.below());
        helper.assertBlockPresent(Blocks.AIR, pistonHead);
        helper.assertBlockPresent(Blocks.AIR, pistonHead.east());
        helper.assertEntityNotPresent(EntityType.ITEM);
        helper.succeed();
    }

    /** A protected counterpart (door lower half, piston base) keeps the whole assembly intact and occluding. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void deniedCounterpartKeepsAssemblyIntact(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos doorUpper = placeDoor(helper, 1);
        BlockPos pistonHead = placeExtendedPiston(helper, 3);
        BlockPos absDoorLower = helper.absolutePos(doorUpper.below());
        BlockPos absPistonBase = helper.absolutePos(pistonHead.east());
        BlockState upperState = helper.getLevel().getBlockState(helper.absolutePos(doorUpper));
        BlockState headState = helper.getLevel().getBlockState(helper.absolutePos(pistonHead));
        BlockState lowerState = helper.getLevel().getBlockState(absDoorLower);
        BlockState baseState = helper.getLevel().getBlockState(absPistonBase);
        WeaponData cannon = weapon("test_block_cannon", 1000.0);
        ServerPlayer shooter = shooter(helper);
        try {
            shooter.setGameMode(GameType.SURVIVAL);
            VETOED.add(absDoorLower);
            VETOED.add(absPistonBase);
            assertForbidden(helper, trace(helper, shooter, 1, cannon), doorUpper, upperState.getBlock());
            assertForbidden(helper, trace(helper, shooter, 3, cannon), pistonHead, Blocks.PISTON_HEAD);
        } finally {
            VETOED.remove(absDoorLower);
            VETOED.remove(absPistonBase);
            remove(shooter);
        }
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.getBlockState(helper.absolutePos(doorUpper)) == upperState
                && level.getBlockState(absDoorLower) == lowerState, "both door halves must stay");
        helper.assertTrue(level.getBlockState(helper.absolutePos(pistonHead)) == headState
                && level.getBlockState(absPistonBase) == baseState, "piston head and base must stay");
        helper.assertEntityNotPresent(EntityType.ITEM);
        helper.succeed();
    }

    /**
     * An upward shot that breaks a brick wall must still pay for the wall's overhang into the cell above, exactly
     * like the same shot through an identical wall that survives: same thickness, remaining damage and
     * penetrations, and a penetration-distance limit between the in-cell part and the full post still stops it.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakingWallStillPaysItsOverhang(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        // Range 3 blocks ends above the wall; 8 units lies between the in-cell part (~3.6) and the post (~12.5).
        WeaponData rifle = weapon("test_wall_rifle", 36.0, 3.0, 90.0);
        WeaponData shortPen = weapon("test_wall_short_pen", 36.0, 3.0, 8.0);
        int[] lanes = {1, 3, 5, 7};
        for (int lane : lanes) {
            helper.setBlock(new BlockPos(2, 3, lane), Blocks.BRICK_WALL);
        }
        primeWall(helper, 1, rifle);
        primeWall(helper, 5, shortPen);

        TraceResult breaking = upwardTrace(helper, 1, rifle);
        TraceResult surviving = upwardTrace(helper, 3, rifle);
        TraceResult breakingShort = upwardTrace(helper, 5, shortPen);
        TraceResult survivingShort = upwardTrace(helper, 7, shortPen);

        TraceResult.BlockImpact broke = firstBlockImpact(helper, breaking, new BlockPos(2, 3, 1));
        TraceResult.BlockImpact damaged = firstBlockImpact(helper, surviving, new BlockPos(2, 3, 3));
        helper.assertValueEqual(broke.outcome(), TraceResult.BlockOutcome.BROKEN, "primed wall outcome");
        helper.assertValueEqual(damaged.outcome(), TraceResult.BlockOutcome.DAMAGED, "fresh wall outcome");
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 3, 1));
        helper.assertTrue(damaged.thicknessUnits() > 8.0, "the post must be thicker than the short limit, got "
                + damaged.thicknessUnits());
        helper.assertTrue(Math.abs(broke.thicknessUnits() - damaged.thicknessUnits()) < EPSILON,
                "broken wall thickness " + broke.thicknessUnits() + " must equal surviving " + damaged.thicknessUnits());
        helper.assertTrue(Math.abs(breaking.remainingDamage() - surviving.remainingDamage()) < EPSILON,
                "remaining damage " + breaking.remainingDamage() + " must equal " + surviving.remainingDamage());
        helper.assertValueEqual(breaking.remainingPenetrations(), surviving.remainingPenetrations(), "penetrations left");

        helper.assertValueEqual(firstBlockImpact(helper, breakingShort, new BlockPos(2, 3, 5)).outcome(),
                TraceResult.BlockOutcome.BROKEN, "primed short-limit wall outcome");
        helper.assertValueEqual(survivingShort.stopReason(), TraceResult.StopReason.PENETRATION, "surviving wall stop");
        helper.assertValueEqual(breakingShort.stopReason(), TraceResult.StopReason.PENETRATION, "breaking wall stop");
        helper.succeed();
    }

    /**
     * A shot that breaks a piston head also removes its base, but must still pay for crossing the base exactly
     * like the same shot through an identical surviving piston: the same damage reaches the target behind it,
     * and a weapon with one penetration left stops at the base instead of hitting that target.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void removedCounterpartStillCostsPenetration(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        // Heavy enough to cross an intact head and base (a 36-damage rifle spends almost everything on the
        // head), yet one hit stays below the head's threshold so the fresh lane survives.
        WeaponData rifle = weapon("test_piston_heavy", 115.0);
        WeaponData singlePass = weapon("test_piston_single", 36.0, 7.0, 90.0, 1);
        int[] lanes = {1, 3, 5, 7};
        BlockPos[] heads = new BlockPos[lanes.length];
        for (int i = 0; i < lanes.length; i++) {
            heads[i] = placeExtendedPiston(helper, lanes[i]);
        }
        // Prime with the single-pass weapon: it stops at the base, so only the head accumulates damage.
        primePistonHead(helper, 1, heads[0], singlePass);
        primePistonHead(helper, 5, heads[2], singlePass);
        Zombie[] targets = new Zombie[lanes.length];
        float[] health = new float[lanes.length];
        for (int i = 0; i < lanes.length; i++) {
            targets[i] = zombieBehind(helper, lanes[i]);
            health[i] = targets[i].getHealth();
        }

        TraceResult breaking = trace(helper, null, 1, rifle);
        TraceResult surviving = trace(helper, null, 3, rifle);
        TraceResult breakingSingle = trace(helper, null, 5, singlePass);
        TraceResult survivingSingle = trace(helper, null, 7, singlePass);

        helper.assertValueEqual(firstBlockImpact(helper, breaking, heads[0]).outcome(), TraceResult.BlockOutcome.BROKEN,
                "primed head outcome");
        helper.assertValueEqual(firstBlockImpact(helper, surviving, heads[1]).outcome(), TraceResult.BlockOutcome.DAMAGED,
                "fresh head outcome");
        helper.assertBlockPresent(Blocks.AIR, heads[0].east());
        assertBlockImpactAt(helper, breaking, 1, heads[0].east());
        assertBlockImpactAt(helper, surviving, 1, heads[1].east());
        double breakingHit = targetHit(helper, breaking, targets[0]);
        double survivingHit = targetHit(helper, surviving, targets[1]);
        helper.assertTrue(Math.abs(breakingHit - survivingHit) < EPSILON,
                "target behind the broken piston took " + breakingHit + ", behind the surviving one " + survivingHit);
        helper.assertValueEqual(breaking.remainingPenetrations(), surviving.remainingPenetrations(), "penetrations left");

        helper.assertValueEqual(firstBlockImpact(helper, breakingSingle, heads[2]).outcome(),
                TraceResult.BlockOutcome.BROKEN, "primed single-pass head outcome");
        for (TraceResult single : List.of(breakingSingle, survivingSingle)) {
            helper.assertValueEqual(single.stopReason(), TraceResult.StopReason.PENETRATION, "single-pass stop reason");
        }
        helper.assertValueEqual(breakingSingle.stoppedAt(), helper.absolutePos(heads[2].east()), "broken piston stop");
        helper.assertValueEqual(survivingSingle.stoppedAt(), helper.absolutePos(heads[3].east()), "surviving piston stop");
        helper.assertValueEqual(targets[2].getHealth(), health[2], "target behind the broken single-pass piston");
        helper.assertValueEqual(targets[3].getHealth(), health[3], "target behind the surviving single-pass piston");
        for (Zombie target : targets) {
            target.discard();
        }
        helper.succeed();
    }

    /**
     * Blocks that lose their support when CS damage breaks a block (carpet, redstone dust) go away without
     * dropping items; an ordinary vanilla break right afterwards still drops normally.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void supportCascadeDropsNoLoot(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos underCarpet = new BlockPos(2, 2, 1);
        BlockPos underDust = new BlockPos(2, 2, 3);
        helper.setBlock(underCarpet, Blocks.STONE);
        helper.setBlock(underCarpet.above(), Blocks.WHITE_CARPET);
        helper.setBlock(underDust, Blocks.STONE);
        helper.setBlock(underDust.above(), Blocks.REDSTONE_WIRE);
        WeaponData cannon = weapon("test_block_cannon", 1000.0);

        helper.assertValueEqual(firstBlockImpact(helper, trace(helper, null, 1, cannon), underCarpet).outcome(),
                TraceResult.BlockOutcome.BROKEN, "stone under carpet");
        helper.assertValueEqual(firstBlockImpact(helper, trace(helper, null, 3, cannon), underDust).outcome(),
                TraceResult.BlockOutcome.BROKEN, "stone under redstone dust");
        helper.assertBlockPresent(Blocks.AIR, underCarpet.above());
        helper.assertBlockPresent(Blocks.AIR, underDust.above());
        helper.assertEntityNotPresent(EntityType.ITEM);

        BlockPos vanilla = new BlockPos(2, 2, 5);
        helper.setBlock(vanilla, Blocks.STONE);
        helper.setBlock(vanilla.above(), Blocks.WHITE_CARPET);
        helper.getLevel().destroyBlock(helper.absolutePos(vanilla), true);
        helper.assertBlockPresent(Blocks.AIR, vanilla.above());
        helper.assertEntityPresent(EntityType.ITEM);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        helper.succeed();
    }

    /**
     * Shooting straight down through a down-facing extended piston breaks the base first; the head (whose arm
     * reaches 4/16 up into the base's cell) goes with it. The bullet must still cross the head as part of the
     * same obstacle: no FORBIDDEN stop at its own removed counterpart, base 12 units, head 4 + 16 = 20 units,
     * and exactly one penetration used for the contiguous pair.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void downwardShotCrossesRemovedHeadAfterBase(GameTestHelper helper) {
        setBlockBreaking(helper, true);
        BlockPos base = new BlockPos(2, 5, 3);
        BlockPos head = base.below();
        helper.setBlock(base.east(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(head, Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.DOWN));
        helper.setBlock(base, Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.DOWN).setValue(PistonBaseBlock.EXTENDED, true));
        WeaponData cannon = weapon("test_block_cannon_down", 1000.0, 5.0, 90.0);

        Vec3 muzzle = helper.absoluteVec(new Vec3(2.5, 7.5, 3.5));
        TraceResult result = Ballistics.trace(helper.getLevel(), null, muzzle, new Vec3(0.0, -1.0, 0.0), cannon);

        helper.assertValueEqual(result.stopReason(), TraceResult.StopReason.RANGE, "stop reason, impacts " + result.impacts());
        helper.assertValueEqual(result.impacts().size(), 2, "impact count, impacts " + result.impacts());
        TraceResult.BlockImpact baseHit = firstBlockImpact(helper, result, base);
        helper.assertValueEqual(baseHit.outcome(), TraceResult.BlockOutcome.BROKEN, "base outcome");
        assertThickness(helper, baseHit, 12.0, "piston base");
        assertBlockImpactAt(helper, result, 1, head);
        TraceResult.BlockImpact headHit = (TraceResult.BlockImpact) result.impacts().get(1);
        helper.assertValueEqual(headHit.outcome(), TraceResult.BlockOutcome.BROKEN, "head outcome");
        assertThickness(helper, headHit, 20.0, "piston head across both cells");
        helper.assertValueEqual(result.remainingPenetrations(), cannon.penetrationCount() - 1, "penetrations left");
        helper.assertBlockPresent(Blocks.AIR, base);
        helper.assertBlockPresent(Blocks.AIR, head);
        helper.assertEntityNotPresent(EntityType.ITEM);
        helper.succeed();
    }

    private static void assertThickness(
            GameTestHelper helper, TraceResult.BlockImpact impact, double expectedUnits, String label) {
        helper.assertTrue(Math.abs(impact.thicknessUnits() - expectedUnits) < 1.0E-4,
                label + " thickness: expected " + expectedUnits + " units, got " + impact.thicknessUnits());
    }

    /** Hits the piston head with identical shots until the next one must break it. */
    private static void primePistonHead(GameTestHelper helper, int lane, BlockPos head, WeaponData weapon) {
        BlockPos abs = helper.absolutePos(head);
        BlockState state = helper.getLevel().getBlockState(abs);
        double threshold = MaterialTable.lookup(state).breakDamage();
        double hit = singleBlockHit(helper, lane, weapon, head, TraceResult.BlockOutcome.DAMAGED);
        while (BlockDamageTracker.damage(helper.getLevel(), abs, state) + hit < threshold) {
            singleBlockHit(helper, lane, weapon, head, TraceResult.BlockOutcome.DAMAGED);
        }
    }

    private static void assertBlockImpactAt(GameTestHelper helper, TraceResult result, int index, BlockPos pos) {
        helper.assertTrue(result.impacts().size() > index && result.impacts().get(index) instanceof TraceResult.BlockImpact block
                        && block.pos().equals(helper.absolutePos(pos)),
                "impact " + index + " must be the block at " + pos + ", got " + result.impacts());
    }

    private static double targetHit(GameTestHelper helper, TraceResult result, Zombie target) {
        for (TraceResult.Impact impact : result.impacts()) {
            if (impact instanceof TraceResult.EntityImpact hit && hit.entity() == target) {
                return hit.csDamage();
            }
        }
        helper.fail("expected a hit on " + target + ", got " + result.impacts());
        return 0.0;
    }

    /** Damages the lane's wall with identical upward shots until the next one must break it. */
    private static void primeWall(GameTestHelper helper, int lane, WeaponData weapon) {
        BlockPos wall = new BlockPos(2, 3, lane);
        BlockPos abs = helper.absolutePos(wall);
        BlockState state = helper.getLevel().getBlockState(abs);
        double threshold = MaterialTable.lookup(state).breakDamage();
        double hit = firstBlockImpact(helper, upwardTrace(helper, lane, weapon), wall).csDamage();
        while (BlockDamageTracker.damage(helper.getLevel(), abs, state) + hit < threshold) {
            helper.assertValueEqual(firstBlockImpact(helper, upwardTrace(helper, lane, weapon), wall).outcome(),
                    TraceResult.BlockOutcome.DAMAGED, "priming hit");
        }
    }

    /** Rises 2 blocks per block east: enters the wall post's west face at y = 3.8, leaves its top at y = 4.5. */
    private static TraceResult upwardTrace(GameTestHelper helper, int lane, WeaponData weapon) {
        Vec3 muzzle = helper.absoluteVec(new Vec3(1.5, 2.3, lane + 0.5));
        return Ballistics.trace(helper.getLevel(), null, muzzle, new Vec3(1.0, 2.0, 0.0), weapon);
    }

    /** Closed oak door facing east on stone, upper half in the lane at y = 2; returns the upper half. */
    private static BlockPos placeDoor(GameTestHelper helper, int lane) {
        BlockPos lower = new BlockPos(2, 1, lane);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        helper.setBlock(lower.below(), Blocks.STONE);
        helper.setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return lower.above();
    }

    /**
     * Extended piston facing west, powered from below: head in the lane at x = 2, base at x = 3 (both on the
     * lane's ray); returns the head.
     */
    private static BlockPos placeExtendedPiston(GameTestHelper helper, int lane) {
        BlockPos head = new BlockPos(2, 2, lane);
        helper.setBlock(head.east().below(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(head, Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.WEST));
        helper.setBlock(head.east(), Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.WEST).setValue(PistonBaseBlock.EXTENDED, true));
        return head;
    }

    /**
     * Shoots the lane until the block is air. Every hit before the last must leave it present and DAMAGED with
     * the tracker holding the summed hit damage; the last must be the only BROKEN report and clear the cracks.
     */
    private static int hitsToBreak(GameTestHelper helper, BlockPos pos, int lane, WeaponData weapon, Block block) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(pos);
        BlockState state = block.defaultBlockState();
        double total = 0.0;
        for (int shot = 1; shot <= MAX_SHOTS; shot++) {
            TraceResult result = trace(helper, null, lane, weapon);
            TraceResult.BlockImpact impact = firstBlockImpact(helper, result, pos);
            if (level.getBlockState(abs).isAir()) {
                helper.assertValueEqual(impact.outcome(), TraceResult.BlockOutcome.BROKEN, "breaking hit outcome");
                helper.assertFalse(BlockDamageTracker.tracked(level, abs), "broken block must clear its cracks");
                helper.assertTrue(shot > 1, block + " must survive the first hit");
                return shot;
            }
            helper.assertValueEqual(impact.outcome(), TraceResult.BlockOutcome.DAMAGED, "hit " + shot + " outcome");
            total += impact.csDamage();
            helper.assertTrue(Math.abs(BlockDamageTracker.damage(level, abs, state) - total) < EPSILON,
                    "tracker must hold the summed hits after hit " + shot);
        }
        helper.fail(block + " survived " + MAX_SHOTS + " hits");
        return MAX_SHOTS;
    }

    private static double singleBlockHit(
            GameTestHelper helper, int lane, WeaponData weapon, BlockPos pos, TraceResult.BlockOutcome expected) {
        TraceResult.BlockImpact impact = firstBlockImpact(helper, trace(helper, null, lane, weapon), pos);
        helper.assertValueEqual(impact.outcome(), expected, "hit outcome at " + pos);
        return impact.csDamage();
    }

    private static TraceResult.BlockImpact firstBlockImpact(GameTestHelper helper, TraceResult result, BlockPos pos) {
        if (!result.impacts().isEmpty() && result.impacts().get(0) instanceof TraceResult.BlockImpact block
                && block.pos().equals(helper.absolutePos(pos))) {
            return block;
        }
        helper.fail("expected the first impact at " + pos + ", got " + result.impacts());
        throw new IllegalStateException("unreachable");
    }

    private static void assertOnlyBlockImpact(
            GameTestHelper helper, TraceResult result, BlockPos pos, TraceResult.BlockOutcome outcome) {
        helper.assertValueEqual(result.impacts().size(), 1, "impact count");
        helper.assertValueEqual(firstBlockImpact(helper, result, pos).outcome(), outcome, "outcome at " + pos);
    }

    private static void assertForbidden(GameTestHelper helper, TraceResult result, BlockPos pos, Block block) {
        helper.assertValueEqual(result.stopReason(), TraceResult.StopReason.FORBIDDEN, "stop reason at " + pos);
        helper.assertValueEqual(result.stoppedAt(), helper.absolutePos(pos), "stopped at");
        assertOnlyBlockImpact(helper, result, pos, TraceResult.BlockOutcome.STOPPED);
        helper.assertBlockPresent(block, pos);
    }

    private static Zombie zombieBehind(GameTestHelper helper, int lane) {
        return helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(4.5, 1.5, lane + 0.5));
    }

    private static TraceResult trace(GameTestHelper helper, ServerPlayer shooter, int lane, WeaponData weapon) {
        Vec3 muzzle = helper.absoluteVec(new Vec3(0.5, 2.5, lane + 0.5));
        return Ballistics.trace(helper.getLevel(), shooter, muzzle, EAST, weapon);
    }

    /** Authored weapon (range ends at x = 7.5, inside the structure); independent of cached weapon data. */
    private static WeaponData weapon(String name, double damage) {
        return weapon(name, damage, 7.0, 90.0);
    }

    private static WeaponData weapon(String name, double damage, double rangeBlocks, double penetrationDistance) {
        return weapon(name, damage, rangeBlocks, penetrationDistance, 4);
    }

    private static WeaponData weapon(
            String name, double damage, double rangeBlocks, double penetrationDistance, int penetrationCount) {
        JsonObject json = new JsonObject();
        json.addProperty("damage", damage);
        json.addProperty("magazine_size", 30);
        json.addProperty("cycle_time_s", 0.1);
        json.addProperty("range", rangeBlocks * WeaponData.UNITS_PER_BLOCK);
        json.addProperty("range_modifier", 0.98);
        json.addProperty("penetration_power", 2.0);
        json.addProperty("penetration_count", penetrationCount);
        json.addProperty("penetration_distance", penetrationDistance);
        json.addProperty("automatic", true);
        return WeaponData.fromJson(CsmcMod.id(name), json);
    }

    @SuppressWarnings("removal")
    private static ServerPlayer shooter(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(6.5, 2.0, 7.5));
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
}
