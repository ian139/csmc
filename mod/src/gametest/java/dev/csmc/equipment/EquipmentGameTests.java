package dev.csmc.equipment;

import com.mojang.authlib.GameProfile;
import dev.csmc.CsmcMod;
import dev.csmc.ballistics.Ballistics;
import dev.csmc.ballistics.BlockBreakPolicy;
import dev.csmc.grenade.GrenadeEffects;
import dev.csmc.item.C4Item;
import dev.csmc.item.HealthshotItem;
import dev.csmc.network.EquipmentActionPayload;
import dev.csmc.network.EquipmentEventPayload;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Healthshot, C4 and defuse GameTests against the real server clocks. Every test lays a stone floor at relative
 * y = 0 inside the empty 8x8x8 structure and uses mock players with distinct profiles. Held actions are timed by
 * level game time ({@link EquipmentSystem#tick} is idempotent per game tick, so callbacks call it before asserting);
 * the planted bomb's fuse and defuse advance in its own entity tick, driven by the level, except where a test ticks
 * a bomb manually inside one callback. Checks around a deadline use {@link #MARGIN} ticks on either side. Global
 * state (gamerules, PvP, teams, difficulty) is only changed in dedicated batches and restored.
 */
public final class EquipmentGameTests implements FabricGameTest {
    private static final double EPS = 1.0E-6;
    /** Ticks either side of a server deadline: callback and server-tick ordering may differ by one tick. */
    private static final int MARGIN = 3;
    /** Defuse heartbeat cadence of the client (must stay within the server lease). */
    private static final int HEARTBEAT_TICKS = 5;
    /** Bomb spot: centre of floor cell (3, 0, 3), just above its top face. */
    private static final Vec3 BOMB = new Vec3(3.5, 1.01, 3.5);
    private static final float HUSK_HEALTH = 1000.0F;

    // ---------------------------------------------------------------- Registry and contract values

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void equipmentRegisteredWithContractDefaults(GameTestHelper helper) {
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(EquipmentRegistry.HEALTHSHOT), CsmcMod.id("healthshot"), "healthshot id");
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(EquipmentRegistry.C4), CsmcMod.id("c4"), "c4 id");
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(EquipmentRegistry.DEFUSE_KIT), CsmcMod.id("defuse_kit"), "defuse kit id");
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(EquipmentRegistry.PLANTED_C4), CsmcMod.id("planted_c4"), "planted c4 id");
        helper.assertValueEqual(EquipmentRegistry.C4_BREAKING.getId(), "csmcC4Breaking", "gamerule name");
        helper.assertTrue(!new GameRules().getBoolean(EquipmentRegistry.C4_BREAKING), "C4 terrain damage is off by default");
        helper.assertValueEqual(HealthshotItem.USE_TICKS, 38, "inject clip length (1.9 s)");
        helper.assertValueEqual(HealthshotItem.COOLDOWN_TICKS, 20, "healthshot cooldown");
        helper.assertValueEqual(C4Item.PLANT_TICKS, 60, "3 s plant");
        helper.assertValueEqual(PlantedC4Entity.FUSE_TICKS, 800, "40 s fuse");
        helper.assertValueEqual(PlantedC4Entity.DEFUSE_TICKS, 200, "10 s defuse");
        helper.assertValueEqual(PlantedC4Entity.KIT_DEFUSE_TICKS, 100, "5 s kit defuse");
        helper.assertTrue(PlantedC4Entity.RADIUS == 8.0, "8 block radius");
        helper.assertTrue(EquipmentSystem.DEFUSE_LEASE_TICKS >= HEARTBEAT_TICKS, "lease outlives the heartbeat cadence");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void c4DamageIsGaussianAndZeroBeyondRadius(GameTestHelper helper) {
        double peak = PlantedC4Entity.damageAt(0.0);
        helper.assertTrue(Math.abs(peak - 500.0 * Ballistics.CS_TO_MC_HEALTH) < EPS, "peak in MC health: " + peak);
        double sigma = PlantedC4Entity.RADIUS / 3.0;
        helper.assertTrue(Math.abs(PlantedC4Entity.damageAt(sigma) / peak - Math.exp(-0.5)) < 1.0E-9, "one sigma");
        helper.assertTrue(Math.abs(PlantedC4Entity.damageAt(2.0 * sigma) / peak - Math.exp(-2.0)) < 1.0E-9, "two sigma");
        double previous = peak;
        for (double d = 0.25; d <= PlantedC4Entity.RADIUS; d += 0.25) {
            double value = PlantedC4Entity.damageAt(d);
            helper.assertTrue(value > 0.0 && value < previous, "strictly falling inside the radius at " + d + ": " + value);
            previous = value;
        }
        helper.assertTrue(PlantedC4Entity.damageAt(PlantedC4Entity.RADIUS) > 0.0, "edge still harms");
        helper.assertTrue(PlantedC4Entity.damageAt(PlantedC4Entity.RADIUS + 1.0E-6) == 0.0, "zero just beyond the radius");
        helper.assertTrue(PlantedC4Entity.damageAt(12.0) == 0.0, "zero far away");
        helper.assertTrue(PlantedC4Entity.damageAt(Double.NaN) == 0.0, "NaN distance harms nothing");
        helper.assertTrue(PlantedC4Entity.damageAt(-1.0) == peak, "negative distance clamps to the peak");
        helper.succeed();
    }

    // ---------------------------------------------------------------- Healthshot

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 140)
    public void healthshotHealsAfterInjectWithoutOverhealThenCoolsDown(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, "eq_heal", GameType.SURVIVAL, new Vec3(1.5, 1.0, 1.5));
        holdInSlot(player, 0, new ItemStack(EquipmentRegistry.HEALTHSHOT, 3));
        player.setHealth(5.0F);
        helper.assertTrue(EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "healthshot use accepted");
        helper.assertTrue(player.isUsingItem() && player.getUseItem() == player.getMainHandItem(), "server holds the use");
        helper.runAfterDelay(HealthshotItem.USE_TICKS - MARGIN, () -> {
            EquipmentSystem.tick(player);
            helper.assertValueEqual(player.getHealth(), 5.0F, "no heal before the inject finishes");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "nothing consumed before completion");
            helper.assertTrue(player.isUsingItem(), "still injecting");
        });
        helper.runAfterDelay(HealthshotItem.USE_TICKS + MARGIN, () -> {
            EquipmentSystem.tick(player);
            helper.assertValueEqual(player.getHealth(), 15.0F, "heals exactly 10");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "consumes exactly one on completion");
            helper.assertTrue(!player.isUsingItem(), "use ends on completion");
            helper.assertTrue(player.getCooldowns().isOnCooldown(EquipmentRegistry.HEALTHSHOT), "cooldown starts");
            helper.assertTrue(!EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "cooldown refuses the next shot");
            helper.assertTrue(!player.isUsingItem(), "refused start does not use");
            // Mock players are not ticked by a connection, so advance the player's own cooldown clock directly.
            tickCooldowns(player, HealthshotItem.COOLDOWN_TICKS - MARGIN);
            helper.assertTrue(player.getCooldowns().isOnCooldown(EquipmentRegistry.HEALTHSHOT), "cooldown not over early");
            tickCooldowns(player, 2 * MARGIN);
            helper.assertTrue(!player.getCooldowns().isOnCooldown(EquipmentRegistry.HEALTHSHOT), "cooldown expires");
            helper.assertValueEqual(player.getHealth(), 15.0F, "health unchanged while cooling down");
            helper.assertTrue(EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "second shot after the cooldown");
            helper.runAfterDelay(HealthshotItem.USE_TICKS + MARGIN, () -> {
                try {
                    EquipmentSystem.tick(player);
                    helper.assertValueEqual(player.getHealth(), 20.0F, "no overheal past max health");
                    helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "second shot consumed one");
                    helper.succeed();
                } finally {
                    remove(player);
                }
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void healthshotCancelsOnReleaseSwitchDeathAndCancel(GameTestHelper helper) {
        floor(helper);
        ServerPlayer release = player(helper, "eq_hs_rel", GameType.SURVIVAL, new Vec3(1.5, 1.0, 1.5));
        ServerPlayer swap = player(helper, "eq_hs_swap", GameType.SURVIVAL, new Vec3(1.5, 1.0, 5.5));
        ServerPlayer dying = player(helper, "eq_hs_die", GameType.SURVIVAL, new Vec3(5.5, 1.0, 1.5));
        ServerPlayer cancelled = player(helper, "eq_hs_can", GameType.SURVIVAL, new Vec3(5.5, 1.0, 5.5));
        List<ServerPlayer> players = List.of(release, swap, dying, cancelled);
        for (ServerPlayer player : players) {
            holdInSlot(player, 0, new ItemStack(EquipmentRegistry.HEALTHSHOT, 2));
            player.setHealth(5.0F);
            helper.assertTrue(EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "start " + player.getScoreboardName());
        }
        helper.runAfterDelay(10, () -> {
            release.releaseUsingItem();
            helper.assertTrue(!release.isUsingItem(), "release stops the use");
            swap.getInventory().selected = 1;
            EquipmentSystem.tick(swap);
            helper.assertTrue(!swap.isUsingItem(), "switching hotbar slot cancels");
            dying.setHealth(0.0F);
            EquipmentSystem.tick(dying);
            helper.assertTrue(!dying.isUsingItem(), "death cancels");
            EquipmentSystem.cancel(cancelled);
            helper.assertTrue(!cancelled.isUsingItem(), "explicit cancel stops the use");
        });
        helper.runAfterDelay(HealthshotItem.USE_TICKS + MARGIN, () -> {
            try {
                for (ServerPlayer player : players) {
                    EquipmentSystem.tick(player);
                    helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 2,
                            "interrupted shot consumes nothing: " + player.getScoreboardName());
                    helper.assertTrue(!player.getCooldowns().isOnCooldown(EquipmentRegistry.HEALTHSHOT),
                            "interrupted shot starts no cooldown: " + player.getScoreboardName());
                }
                helper.assertValueEqual(release.getHealth(), 5.0F, "released shot heals nothing");
                helper.assertValueEqual(swap.getHealth(), 5.0F, "switched shot heals nothing");
                helper.assertValueEqual(dying.getHealth(), 0.0F, "dead player is not revived");
                helper.assertValueEqual(cancelled.getHealth(), 5.0F, "cancelled shot heals nothing");
                helper.assertTrue(EquipmentSystem.startUse(release, InteractionHand.MAIN_HAND), "release is not penalised");
                helper.succeed();
            } finally {
                players.forEach(EquipmentGameTests::remove);
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void healthshotPacketsValidatedAndSpamHealsOnce(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, "eq_hs_pkt", GameType.SURVIVAL, new Vec3(1.5, 1.0, 1.5));
        helper.assertTrue(!EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "empty hand refused");
        holdInSlot(player, 0, new ItemStack(Items.STONE, 4));
        helper.assertTrue(!EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "non-equipment refused");
        holdInSlot(player, 0, new ItemStack(EquipmentRegistry.HEALTHSHOT, 3));
        player.setGameMode(GameType.SPECTATOR);
        helper.assertTrue(!EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "spectator refused");
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(4.0F);

        EquipmentSystem.handle(player, action("start", -1, -1));
        helper.assertTrue(!player.isUsingItem(), "negative sequence ignored");
        EquipmentSystem.handle(player, action("plant", -1, 1));
        helper.assertTrue(!player.isUsingItem(), "unknown action ignored");
        EquipmentSystem.handle(player, action("start", -1, 5));
        helper.assertTrue(player.isUsingItem(), "held start accepted");
        EquipmentSystem.handle(player, action("cancel", -1, 5));
        helper.assertTrue(player.isUsingItem(), "replayed sequence ignored");
        EquipmentSystem.handle(player, action("cancel", -1, 4));
        helper.assertTrue(player.isUsingItem(), "older sequence ignored");
        EquipmentSystem.handle(player, action("cancel", -1, 6));
        helper.assertTrue(!player.isUsingItem(), "fresh cancel accepted");
        EquipmentSystem.handle(player, action("start", -1, 6));
        helper.assertTrue(!player.isUsingItem(), "replayed start ignored");
        EquipmentSystem.handle(player, action("start", -1, 7));
        helper.assertTrue(!player.isUsingItem(), "fresh restart waits until the next server tick");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "packets alone consume nothing");

        int[] sequence = {7};
        helper.onEachTick(() -> {
            // A spamming client: several fresh starts and a replay every tick, before and after completion.
            for (int i = 0; i < 3; i++) {
                EquipmentSystem.handle(player, action("start", -1, ++sequence[0]));
            }
            EquipmentSystem.handle(player, action("start", -1, sequence[0]));
            EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND);
        });
        helper.runAfterDelay(1, () -> {
            EquipmentSystem.handle(player, action("start", -1, ++sequence[0]));
            helper.assertTrue(player.isUsingItem(), "fresh starts accepted on the next tick");
        });
        helper.runAfterDelay(1 + HealthshotItem.USE_TICKS - MARGIN, () -> {
            EquipmentSystem.tick(player);
            helper.assertValueEqual(player.getHealth(), 4.0F, "spam cannot finish the inject early");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "spam consumes nothing early");
        });
        helper.runAfterDelay(1 + HealthshotItem.USE_TICKS + MARGIN, () -> {
            EquipmentSystem.tick(player);
            helper.assertValueEqual(player.getHealth(), 14.0F, "spam does not reset the clock: healed on time");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "exactly one consumed");
        });
        helper.runAfterDelay(1 + HealthshotItem.USE_TICKS + 3 * MARGIN, () -> {
            try {
                EquipmentSystem.tick(player);
                helper.assertValueEqual(player.getHealth(), 14.0F, "spam after completion heals nothing more");
                helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "spam after completion consumes nothing");
                helper.assertTrue(!player.isUsingItem(), "cooldown keeps spam from starting a new use");
                helper.succeed();
            } finally {
                remove(player);
            }
        });
    }

    // ---------------------------------------------------------------- Planting

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void c4PlantsAfterHoldOnSolidTopFaceAndIsConsumed(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, "eq_plant", GameType.SURVIVAL, new Vec3(3.5, 1.0, 3.5));
        look(player, -90.0F, 90.0F);
        holdInSlot(player, 0, new ItemStack(EquipmentRegistry.C4));
        BlockHitResult target = EquipmentSystem.plantingTarget(player);
        helper.assertTrue(target != null, "floor below is a planting target");
        helper.assertValueEqual(target.getBlockPos(), helper.absolutePos(new BlockPos(3, 0, 3)), "support block");
        helper.assertValueEqual(target.getDirection(), Direction.UP, "top face");
        helper.assertTrue(EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "plant accepted");
        helper.assertTrue(player.isUsingItem(), "planting holds the use");
        helper.runAfterDelay(C4Item.PLANT_TICKS - MARGIN, () -> {
            EquipmentSystem.tick(player);
            helper.assertValueEqual(bombs(helper).size(), 0, "nothing planted before 3 s");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "C4 kept while planting");
        });
        helper.runAfterDelay(C4Item.PLANT_TICKS + MARGIN, () -> {
            try {
                EquipmentSystem.tick(player);
                List<PlantedC4Entity> bombs = bombs(helper);
                helper.assertValueEqual(bombs.size(), 1, "exactly one bomb planted");
                PlantedC4Entity bomb = bombs.get(0);
                helper.assertValueEqual(bomb.getOwnerId(), player.getUUID(), "planter owns the bomb");
                helper.assertValueEqual(bomb.blockPosition(), helper.absolutePos(new BlockPos(3, 1, 3)), "rests on the aimed face");
                helper.assertTrue(Math.abs(bomb.getY() - helper.absoluteVec(new Vec3(0.0, 1.0, 0.0)).y) < 0.05, "on the top face");
                helper.assertTrue(player.getMainHandItem().isEmpty(), "C4 consumed on plant");
                helper.assertTrue(!player.isUsingItem(), "use ends on plant");
                int fuse = bomb.getFuseTicks();
                helper.assertTrue(fuse <= PlantedC4Entity.FUSE_TICKS && fuse > PlantedC4Entity.FUSE_TICKS - 2 * MARGIN,
                        "fuse just started: " + fuse);
                helper.succeed();
            } finally {
                bombs(helper).forEach(Entity::discard);
                remove(player);
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void c4PlantRequiresSolidTopFaceWithinReach(GameTestHelper helper) {
        floor(helper);
        for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(6, 1, z), Blocks.STONE);
            helper.setBlock(new BlockPos(6, 2, z), Blocks.STONE);
        }
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        ServerPlayer player = player(helper, "eq_aim", GameType.SURVIVAL, new Vec3(3.5, 1.0, 3.5));
        try {
            holdInSlot(player, 0, new ItemStack(EquipmentRegistry.C4));
            look(player, -90.0F, -90.0F);
            refusedPlant(helper, player, "looking at the sky");
            look(player, -90.0F, 0.0F);
            refusedPlant(helper, player, "looking at a wall side face");
            place(helper, player, new Vec3(3.5, 6.0, 3.5));
            look(player, -90.0F, 90.0F);
            refusedPlant(helper, player, "floor beyond block reach");
            place(helper, player, new Vec3(1.5, 1.0, 1.5));
            refusedPlant(helper, player, "bottom slab is not a solid top face");
            place(helper, player, new Vec3(3.5, 1.0, 3.5));
            helper.assertTrue(EquipmentSystem.plantingTarget(player) != null, "control: solid floor in reach accepted");

            ServerLevel level = helper.getLevel();
            PlantedC4Entity first = PlantedC4Entity.plant(level, player, helper.absoluteVec(new Vec3(4.5, 1.01, 1.5)));
            helper.assertTrue(first != null, "factory plants in free space");
            helper.assertTrue(PlantedC4Entity.plant(level, player, helper.absoluteVec(new Vec3(4.5, 1.01, 1.5))) == null,
                    "no overlapping second bomb");
            helper.assertTrue(PlantedC4Entity.plant(level, player, helper.absoluteVec(new Vec3(4.5, 0.5, 1.5))) == null,
                    "no bomb inside a block");
            helper.assertValueEqual(bombs(helper).size(), 1, "only the valid factory call planted");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "refusals consume nothing");
        } finally {
            bombs(helper).forEach(Entity::discard);
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void c4PlantInterruptedByAimLossReleaseOrSwitch(GameTestHelper helper) {
        floor(helper);
        ServerPlayer aim = player(helper, "eq_pl_aim", GameType.SURVIVAL, new Vec3(1.5, 1.0, 1.5));
        ServerPlayer release = player(helper, "eq_pl_rel", GameType.SURVIVAL, new Vec3(5.5, 1.0, 5.5));
        ServerPlayer swap = player(helper, "eq_pl_swap", GameType.SURVIVAL, new Vec3(1.5, 1.0, 5.5));
        List<ServerPlayer> players = List.of(aim, release, swap);
        for (ServerPlayer player : players) {
            look(player, -90.0F, 90.0F);
            holdInSlot(player, 0, new ItemStack(EquipmentRegistry.C4));
            helper.assertTrue(EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "start " + player.getScoreboardName());
        }
        helper.runAfterDelay(20, () -> {
            look(aim, -90.0F, -90.0F);
            EquipmentSystem.tick(aim);
            helper.assertTrue(!aim.isUsingItem(), "looking away cancels the plant");
            release.releaseUsingItem();
            helper.assertTrue(!release.isUsingItem(), "release cancels the plant");
            swap.getInventory().selected = 1;
            EquipmentSystem.tick(swap);
            helper.assertTrue(!swap.isUsingItem(), "switching slot cancels the plant");
        });
        helper.runAfterDelay(C4Item.PLANT_TICKS + MARGIN, () -> {
            try {
                players.forEach(EquipmentSystem::tick);
                helper.assertValueEqual(bombs(helper).size(), 0, "no interrupted plant completes");
                for (ServerPlayer player : players) {
                    helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 1,
                            "interrupted plant keeps the C4: " + player.getScoreboardName());
                }
                helper.succeed();
            } finally {
                bombs(helper).forEach(Entity::discard);
                players.forEach(EquipmentGameTests::remove);
            }
        });
    }

    /**
     * The plant lands where the server last validated the aim, not where it was first aimed: after retreating to
     * near block reach and re-aiming at the near edge of the same support block, the first aim point is out of
     * reach. A completed plant must sit at the final validated hit (reachable, unobstructed); refusing the plant
     * is also acceptable, but never planting at the stale first point.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void c4PlantLandsAtTheFinalValidatedAimNotTheFirst(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, "eq_pl_reach", GameType.SURVIVAL, new Vec3(5.0, 1.0, 3.5));
        holdInSlot(player, 0, new ItemStack(EquipmentRegistry.C4));
        BlockPos support = helper.absolutePos(new BlockPos(7, 0, 3));
        Vec3 farEdge = helper.absoluteVec(new Vec3(7.7, 1.0, 3.5));
        Vec3 nearEdge = helper.absoluteVec(new Vec3(7.1, 1.0, 3.5));
        aimAt(player, farEdge);
        BlockHitResult first = EquipmentSystem.plantingTarget(player);
        helper.assertTrue(first != null, "far edge of the support is a planting target");
        helper.assertValueEqual(first.getBlockPos(), support, "first aim support block");
        helper.assertTrue(first.getLocation().distanceTo(farEdge) < 0.05, "first aim at the far edge: " + first.getLocation());
        helper.assertTrue(EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), "plant accepted");
        helper.runAfterDelay(10, () -> {
            // Retreat and re-aim atomically (between server ticks) at the near edge of the same support block.
            place(helper, player, new Vec3(3.2, 1.0, 3.5));
            aimAt(player, nearEdge);
            BlockHitResult retreat = EquipmentSystem.plantingTarget(player);
            helper.assertTrue(retreat != null, "near edge is a planting target after the retreat");
            helper.assertValueEqual(retreat.getBlockPos(), support, "re-aimed at the same support block");
            helper.assertTrue(retreat.getLocation().distanceTo(nearEdge) < 0.05, "re-aimed at the near edge");
            Vec3 eye = player.getEyePosition();
            double range = player.blockInteractionRange();
            helper.assertTrue(eye.distanceTo(retreat.getLocation()) < range - 0.2,
                    "fixture: near edge within block reach: " + eye.distanceTo(retreat.getLocation()));
            helper.assertTrue(eye.distanceTo(first.getLocation()) > range + 0.2,
                    "fixture: first aim point now beyond block reach: " + eye.distanceTo(first.getLocation()));
        });
        helper.runAfterDelay(C4Item.PLANT_TICKS + MARGIN, () -> {
            try {
                EquipmentSystem.tick(player);
                helper.assertTrue(!player.isUsingItem(), "plant use ended");
                List<PlantedC4Entity> bombs = bombs(helper);
                if (bombs.isEmpty()) {
                    helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "refused plant keeps the C4");
                } else {
                    helper.assertValueEqual(bombs.size(), 1, "at most one bomb planted");
                    Vec3 bomb = bombs.get(0).position();
                    Vec3 eye = player.getEyePosition();
                    BlockHitResult last = EquipmentSystem.plantingTarget(player);
                    helper.assertTrue(last != null, "final aim still validated");
                    helper.assertTrue(bomb.distanceTo(first.getLocation()) > 0.3, "not planted at the stale first aim: " + bomb);
                    helper.assertTrue(bomb.distanceTo(last.getLocation()) < 0.05,
                            "planted at the final validated hit " + last.getLocation() + ", got " + bomb);
                    helper.assertTrue(eye.distanceTo(bomb) <= player.blockInteractionRange(),
                            "planted within block reach: " + eye.distanceTo(bomb));
                    helper.assertTrue(helper.getLevel().clip(new ClipContext(eye, bomb, ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS, "planted spot unobstructed");
                    helper.assertTrue(player.getMainHandItem().isEmpty(), "C4 consumed on plant");
                }
                helper.succeed();
            } finally {
                bombs(helper).forEach(Entity::discard);
                remove(player);
            }
        });
    }

    // ---------------------------------------------------------------- Fuse and blast

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void fuseCountsDownAndDetonatesExactlyOnce(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_fuse", GameType.SURVIVAL, new Vec3(3.5, 20.0, 3.5));
        Husk husk = husk(helper, new Vec3(5.0, 1.0, 3.5));
        try {
            PlantedC4Entity bomb = plant(helper, owner);
            helper.assertValueEqual(bomb.getFuseTicks(), PlantedC4Entity.FUSE_TICKS, "full fuse at plant");
            helper.assertValueEqual(bomb.getOwnerId(), owner.getUUID(), "owner");
            helper.assertTrue(!bomb.isDefused() && !bomb.isDetonated() && bomb.getDefuserId() == null, "fresh state");
            for (int i = 0; i < PlantedC4Entity.FUSE_TICKS - 1; i++) {
                bomb.tick();
            }
            helper.assertValueEqual(bomb.getFuseTicks(), 1, "one tick left");
            helper.assertTrue(!bomb.isDetonated() && !bomb.isRemoved(), "no early detonation");
            helper.assertValueEqual(husk.getHealth(), HUSK_HEALTH, "no harm before the fuse ends");
            bomb.tick();
            helper.assertTrue(bomb.isDetonated() && bomb.isRemoved(), "detonates when the fuse runs out");
            float afterBlast = husk.getHealth();
            helper.assertTrue(afterBlast < HUSK_HEALTH, "blast hurt the husk");
            bomb.tick();
            bomb.detonate();
            helper.assertTrue(bombs(helper).isEmpty(), "no bomb left");
            helper.assertValueEqual(husk.getHealth(), afterBlast, "exactly one detonation");
        } finally {
            bombs(helper).forEach(Entity::discard);
            husk.discard();
            remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void fuseRunsWithTheLevelAndPersists(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_save", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        PlantedC4Entity original = plant(helper, owner);
        PlantedC4Entity[] current = {original};
        int[] saved = {0};
        helper.runAfterDelay(20, () -> {
            int fuse = original.getFuseTicks();
            helper.assertTrue(fuse >= PlantedC4Entity.FUSE_TICKS - 20 - MARGIN && fuse <= PlantedC4Entity.FUSE_TICKS - 20 + MARGIN,
                    "fuse follows level ticks: " + fuse);
            CompoundTag tag = new CompoundTag();
            original.saveWithoutId(tag);
            saved[0] = fuse;
            original.discard();
            PlantedC4Entity copy = EquipmentRegistry.PLANTED_C4.create(helper.getLevel());
            helper.assertTrue(copy != null, "entity type creates");
            copy.load(tag);
            copy.setUUID(UUID.randomUUID());
            helper.assertValueEqual(copy.getFuseTicks(), fuse, "saved remaining fuse");
            helper.assertValueEqual(copy.getOwnerId(), owner.getUUID(), "saved owner");
            helper.assertTrue(!copy.isDefused() && !copy.isDetonated(), "saved live state");
            helper.getLevel().addFreshEntity(copy);
            current[0] = copy;
        });
        PlantedC4Entity[] spent = {null};
        helper.runAfterDelay(30, () -> {
            try {
                int fuse = current[0].getFuseTicks();
                helper.assertTrue(fuse >= saved[0] - 10 - MARGIN && fuse <= saved[0] - 10 + MARGIN,
                        "reloaded fuse continues from the save: " + fuse + " after " + saved[0]);
                helper.assertTrue(!current[0].isRemoved(), "reloaded bomb lives");
                helper.assertValueEqual(bombs(helper).size(), 1, "only the reloaded bomb");
                // A terminal (detonated) save must not come back as an immortal ghost.
                current[0].detonate();
                CompoundTag tag = new CompoundTag();
                current[0].saveWithoutId(tag);
                PlantedC4Entity ghost = EquipmentRegistry.PLANTED_C4.create(helper.getLevel());
                helper.assertTrue(ghost != null, "entity type creates");
                ghost.load(tag);
                ghost.setUUID(UUID.randomUUID());
                helper.assertTrue(ghost.isDetonated(), "saved detonated flag");
                helper.getLevel().addFreshEntity(ghost);
                spent[0] = ghost;
            } catch (RuntimeException e) {
                bombs(helper).forEach(Entity::discard);
                remove(owner);
                throw e;
            }
        });
        helper.runAfterDelay(30 + MARGIN, () -> {
            try {
                helper.assertTrue(spent[0].isRemoved(), "loaded terminal bomb is discarded on its first tick");
                helper.assertTrue(bombs(helper).isEmpty(), "no ghost bomb left");
                helper.succeed();
            } finally {
                bombs(helper).forEach(Entity::discard);
                remove(owner);
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_c4_no_terrain")
    public void blastFallsOffWithDistanceAndSparesTerrainByDefault(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        boolean priorC4 = level.getGameRules().getBoolean(EquipmentRegistry.C4_BREAKING);
        boolean priorBlocks = level.getGameRules().getBoolean(BlockBreakPolicy.BLOCK_BREAKING_RULE);
        BlockPos glass = new BlockPos(2, 1, 3);
        helper.setBlock(glass, Blocks.GLASS);
        ServerPlayer owner = player(helper, "eq_blast", GameType.SURVIVAL, new Vec3(3.5, 20.0, 3.5));
        Husk near = husk(helper, new Vec3(5.0, 1.0, 3.5));
        Husk mid = husk(helper, new Vec3(3.5, 1.0, 7.5));
        Husk far = husk(helper, new Vec3(3.5, 11.0, 3.5));
        try {
            level.getGameRules().getRule(EquipmentRegistry.C4_BREAKING).set(false, server);
            // Even with the general csmc block-breaking rule on, C4 terrain damage stays off by its own rule.
            level.getGameRules().getRule(BlockBreakPolicy.BLOCK_BREAKING_RULE).set(true, server);
            PlantedC4Entity bomb = plant(helper, owner);
            bomb.detonate();
            helper.assertTrue(bomb.isDetonated() && bomb.isRemoved(), "detonated and removed");
            float nearTaken = HUSK_HEALTH - near.getHealth();
            float midTaken = HUSK_HEALTH - mid.getHealth();
            helper.assertTrue(nearTaken > midTaken && midTaken > 0.0F,
                    "damage falls off with distance: near " + nearTaken + " mid " + midTaken);
            double nearDistance = 1.5 - 0.3; // husk box half-width 0.3, bomb centre within its height
            helper.assertTrue(nearTaken <= PlantedC4Entity.damageAt(nearDistance) + 1.0E-3,
                    "never more than the falloff value: " + nearTaken);
            helper.assertValueEqual(far.getHealth(), HUSK_HEALTH, "nothing beyond the radius is harmed");
            helper.assertValueEqual(owner.getHealth(), owner.getMaxHealth(), "owner beyond the radius is unharmed");
            helper.assertBlockPresent(Blocks.GLASS, glass);
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 0, 3));
        } finally {
            level.getGameRules().getRule(EquipmentRegistry.C4_BREAKING).set(priorC4, server);
            level.getGameRules().getRule(BlockBreakPolicy.BLOCK_BREAKING_RULE).set(priorBlocks, server);
            bombs(helper).forEach(Entity::discard);
            near.discard();
            mid.discard();
            far.discard();
            remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_c4_terrain")
    public void blastBreaksTerrainOnlyWithGamerule(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        boolean priorC4 = level.getGameRules().getBoolean(EquipmentRegistry.C4_BREAKING);
        boolean priorBlocks = level.getGameRules().getBoolean(BlockBreakPolicy.BLOCK_BREAKING_RULE);
        BlockPos glass = new BlockPos(2, 1, 3);
        helper.setBlock(glass, Blocks.GLASS);
        ServerPlayer owner = player(helper, "eq_terrain", GameType.SURVIVAL, new Vec3(3.5, 20.0, 3.5));
        try {
            level.getGameRules().getRule(EquipmentRegistry.C4_BREAKING).set(true, server);
            level.getGameRules().getRule(BlockBreakPolicy.BLOCK_BREAKING_RULE).set(true, server);
            plant(helper, owner).detonate();
            helper.assertBlockPresent(Blocks.AIR, glass);
        } finally {
            level.getGameRules().getRule(EquipmentRegistry.C4_BREAKING).set(priorC4, server);
            level.getGameRules().getRule(BlockBreakPolicy.BLOCK_BREAKING_RULE).set(priorBlocks, server);
            bombs(helper).forEach(Entity::discard);
            remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unresolvedOwnerBlastHarmsNothing(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_gone", GameType.SURVIVAL, new Vec3(3.5, 20.0, 3.5));
        Husk husk = husk(helper, new Vec3(5.0, 1.0, 3.5));
        try {
            PlantedC4Entity bomb = plant(helper, owner);
            remove(owner);
            helper.assertTrue(GrenadeEffects.resolveOwner(helper.getLevel(), owner.getUUID()) == null, "owner unresolved");
            bomb.detonate();
            helper.assertTrue(bomb.isDetonated() && bomb.isRemoved(), "still detonates and goes away");
            helper.assertValueEqual(husk.getHealth(), HUSK_HEALTH, "ownerless blast harms nothing");
        } finally {
            bombs(helper).forEach(Entity::discard);
            husk.discard();
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_c4_pvp_off")
    public void pvpOffSparesPlayers(GameTestHelper helper) {
        floor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        boolean pvp = server.isPvpAllowed();
        Difficulty difficulty = server.getWorldData().getDifficulty();
        ServerPlayer owner = player(helper, "eq_pvpa", GameType.SURVIVAL, new Vec3(3.5, 20.0, 3.5));
        ServerPlayer target = player(helper, "eq_pvpb", GameType.SURVIVAL, new Vec3(5.0, 1.0, 3.5));
        Husk husk = husk(helper, new Vec3(2.0, 1.0, 3.5));
        try {
            combatReady(server, difficulty, target);
            server.setPvpAllowed(false);
            float start = target.getHealth();
            plant(helper, owner).detonate();
            helper.assertValueEqual(target.getHealth(), start, "PvP off spares the player");
            helper.assertTrue(husk.getHealth() < HUSK_HEALTH, "mobs are still hit");
        } finally {
            server.setPvpAllowed(pvp);
            restoreDifficulty(server, difficulty);
            bombs(helper).forEach(Entity::discard);
            husk.discard();
            remove(owner);
            remove(target);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_c4_teams")
    public void teamFriendlyFireGuard(GameTestHelper helper) {
        floor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        boolean pvp = server.isPvpAllowed();
        Difficulty difficulty = server.getWorldData().getDifficulty();
        ServerPlayer owner = player(helper, "eq_teama", GameType.SURVIVAL, new Vec3(3.5, 20.0, 3.5));
        ServerPlayer mate = player(helper, "eq_teamb", GameType.SURVIVAL, new Vec3(5.0, 1.0, 3.5));
        ServerPlayer enemy = player(helper, "eq_teamc", GameType.SURVIVAL, new Vec3(2.0, 1.0, 3.5));
        PlayerTeam team = server.getScoreboard().addPlayerTeam("eq_c4_team");
        try {
            combatReady(server, difficulty, mate, enemy);
            server.setPvpAllowed(true);
            team.setAllowFriendlyFire(false);
            server.getScoreboard().addPlayerToTeam(owner.getScoreboardName(), team);
            server.getScoreboard().addPlayerToTeam(mate.getScoreboardName(), team);
            float mateStart = mate.getHealth();
            float enemyStart = enemy.getHealth();
            plant(helper, owner).detonate();
            helper.assertValueEqual(mate.getHealth(), mateStart, "teammate spared with friendly fire off");
            helper.assertTrue(enemy.getHealth() < enemyStart, "enemy hit: " + enemy.getHealth());
        } finally {
            server.getScoreboard().removePlayerTeam(team);
            server.setPvpAllowed(pvp);
            restoreDifficulty(server, difficulty);
            bombs(helper).forEach(Entity::discard);
            remove(owner);
            remove(mate);
            remove(enemy);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- Defuse

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = PlantedC4Entity.DEFUSE_TICKS + 40)
    public void defuseTakesTenSecondsBareHanded(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_def_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        Husk husk = husk(helper, new Vec3(5.0, 1.0, 5.5));
        PlantedC4Entity bomb = plant(helper, owner);
        ServerPlayer defuser = defuser(helper, "eq_def", new Vec3(3.5, 1.0, 2.0), bomb);
        Lease lease = new Lease(helper, defuser, bomb);
        lease.send();
        helper.assertValueEqual(bomb.getDefuserId(), defuser.getUUID(), "defuse started");
        helper.assertTrue(EquipmentSystem.isDefusing(defuser, bomb), "server session active");
        helper.runAfterDelay(PlantedC4Entity.DEFUSE_TICKS - MARGIN, () -> {
            helper.assertTrue(!bomb.isDefused() && !bomb.isRemoved(), "not defused before 10 s");
            int ticks = bomb.getDefuseTicks();
            helper.assertTrue(ticks >= PlantedC4Entity.DEFUSE_TICKS - 3 * MARGIN && ticks < PlantedC4Entity.DEFUSE_TICKS,
                    "defuse progressed continuously: " + ticks);
            helper.assertTrue(Math.abs(bomb.getDefuseProgress() - ticks / (float) PlantedC4Entity.DEFUSE_TICKS) < 1.0E-4,
                    "progress fraction");
            helper.assertTrue(bomb.getFuseTicks() < PlantedC4Entity.FUSE_TICKS - PlantedC4Entity.DEFUSE_TICKS + 2 * MARGIN,
                    "fuse keeps running while defusing");
        });
        helper.runAfterDelay(PlantedC4Entity.DEFUSE_TICKS + MARGIN, () -> {
            try {
                helper.assertTrue(bomb.isDefused() && bomb.isRemoved() && !bomb.isDetonated(), "defused after 10 s");
                helper.assertTrue(bombs(helper).isEmpty(), "defused bomb removed");
                helper.assertValueEqual(husk.getHealth(), HUSK_HEALTH, "defused bomb never exploded");
                helper.assertTrue(!EquipmentSystem.isDefusing(defuser, bomb), "session ends with the bomb");
                helper.succeed();
            } finally {
                lease.active = false;
                bombs(helper).forEach(Entity::discard);
                husk.discard();
                remove(defuser);
                remove(owner);
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 2 * PlantedC4Entity.KIT_DEFUSE_TICKS + 40)
    public void kitAnywhereInInventoryHalvesDefuseAndIsReusable(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_kit_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        PlantedC4Entity first = plant(helper, owner);
        ServerPlayer defuser = defuser(helper, "eq_kit", new Vec3(3.5, 1.0, 2.0), first);
        defuser.getInventory().setItem(8, new ItemStack(EquipmentRegistry.DEFUSE_KIT));
        Lease lease = new Lease(helper, defuser, first);
        lease.send();
        helper.assertValueEqual(first.getDefuserId(), defuser.getUUID(), "kit defuse started");
        helper.runAfterDelay(PlantedC4Entity.KIT_DEFUSE_TICKS - MARGIN, () ->
                helper.assertTrue(!first.isDefused() && !first.isRemoved(), "not defused before 5 s"));
        helper.runAfterDelay(PlantedC4Entity.KIT_DEFUSE_TICKS + MARGIN, () -> {
            helper.assertTrue(first.isDefused() && first.isRemoved() && !first.isDetonated(), "kit defuse in 5 s");
            helper.assertTrue(defuser.getInventory().getItem(8).is(EquipmentRegistry.DEFUSE_KIT)
                    && defuser.getInventory().getItem(8).getCount() == 1, "kit is not consumed");
            PlantedC4Entity second = plant(helper, owner);
            aim(defuser, second);
            lease.bomb = second;
            lease.send();
            helper.assertValueEqual(second.getDefuserId(), defuser.getUUID(), "second defuse with the same kit");
            helper.runAfterDelay(PlantedC4Entity.KIT_DEFUSE_TICKS - MARGIN, () ->
                    helper.assertTrue(!second.isDefused(), "second defuse not early"));
            helper.runAfterDelay(PlantedC4Entity.KIT_DEFUSE_TICKS + MARGIN, () -> {
                try {
                    helper.assertTrue(second.isDefused() && second.isRemoved(), "reused kit defuses in 5 s again");
                    helper.assertTrue(defuser.getInventory().getItem(8).is(EquipmentRegistry.DEFUSE_KIT), "kit kept again");
                    helper.succeed();
                } finally {
                    lease.active = false;
                    bombs(helper).forEach(Entity::discard);
                    remove(defuser);
                    remove(owner);
                }
            });
        });
    }

    /**
     * Each interruption (explicit cancel, aim loss, slot switch, missed heartbeats) resets progress to zero; the next
     * valid heartbeat restarts from zero without an explicit cancel. Uses a kit: without resets it would finish early.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 260)
    public void defuseInterruptionsResetProgress(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_rst_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        PlantedC4Entity bomb = plant(helper, owner);
        ServerPlayer defuser = defuser(helper, "eq_rst", new Vec3(3.5, 1.0, 2.0), bomb);
        defuser.getInventory().setItem(8, new ItemStack(EquipmentRegistry.DEFUSE_KIT));
        Lease lease = new Lease(helper, defuser, bomb);
        lease.send();
        helper.assertTrue(syncedUuids(bomb).contains(defuser.getUUID()), "defuser identity synced to clients");
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(bomb.getDefuseTicks() >= 20 - MARGIN, "progress before cancel: " + bomb.getDefuseTicks());
            lease.cancel();
            assertReset(helper, bomb, defuser, "explicit cancel");
            helper.assertTrue(syncedUuids(bomb).isEmpty(), "cancel clears the synced defuser identity");
        });
        helper.runAfterDelay(40, () -> {
            assertRestarted(helper, bomb, defuser, 40 - 20, "heartbeat after cancel");
            // Heartbeats pause briefly (well inside the lease) so the reset is observed before a renewal restarts it.
            lease.active = false;
            look(defuser, defuser.getYRot(), -90.0F);
        });
        helper.runAfterDelay(42, () -> {
            assertReset(helper, bomb, defuser, "aim loss");
            aim(defuser, bomb);
            lease.active = true; // the same server session renews: no explicit cancel needed
        });
        helper.runAfterDelay(60, () -> {
            assertRestarted(helper, bomb, defuser, 60 - 42, "heartbeat after re-aiming");
            lease.active = false;
            defuser.getInventory().selected = 1;
        });
        helper.runAfterDelay(62, () -> {
            assertReset(helper, bomb, defuser, "slot switch");
            lease.active = true;
        });
        helper.runAfterDelay(80, () -> {
            assertRestarted(helper, bomb, defuser, 80 - 62, "heartbeat on the new slot");
            lease.active = false;
        });
        helper.runAfterDelay(80 + EquipmentSystem.DEFUSE_LEASE_TICKS + 2 * MARGIN, () -> {
            assertReset(helper, bomb, defuser, "missed heartbeats");
            lease.active = true;
            lease.send();
        });
        int restart = 80 + EquipmentSystem.DEFUSE_LEASE_TICKS + 2 * MARGIN;
        helper.runAfterDelay(restart + PlantedC4Entity.KIT_DEFUSE_TICKS - 2 * MARGIN, () ->
                helper.assertTrue(!bomb.isDefused() && !bomb.isRemoved(),
                        "earlier progress never carries over: " + bomb.getDefuseTicks()));
        helper.runAfterDelay(restart + PlantedC4Entity.KIT_DEFUSE_TICKS + MARGIN, () -> {
            try {
                helper.assertTrue(bomb.isDefused() && bomb.isRemoved() && !bomb.isDetonated(), "uninterrupted run defuses");
                helper.succeed();
            } finally {
                lease.active = false;
                bombs(helper).forEach(Entity::discard);
                remove(defuser);
                remove(owner);
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void oneDefuserAtATimeAndDeathResets(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_one_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        PlantedC4Entity bomb = plant(helper, owner);
        ServerPlayer first = defuser(helper, "eq_one_a", new Vec3(3.5, 1.0, 2.0), bomb);
        ServerPlayer second = defuser(helper, "eq_one_b", new Vec3(3.5, 1.0, 5.0), bomb);
        Lease lease = new Lease(helper, first, bomb);
        lease.send();
        helper.assertValueEqual(bomb.getDefuserId(), first.getUUID(), "first defuser holds the bomb");
        helper.assertTrue(!EquipmentSystem.startDefuse(second, bomb), "second defuser refused while the first is active");
        helper.assertValueEqual(bomb.getDefuserId(), first.getUUID(), "refusal keeps the first defuser");
        helper.assertTrue(!EquipmentSystem.isDefusing(second, bomb), "refused player has no session");
        helper.runAfterDelay(20, () -> first.setHealth(0.0F));
        helper.runAfterDelay(20 + MARGIN, () -> {
            try {
                assertReset(helper, bomb, first, "defuser death");
                helper.assertTrue(EquipmentSystem.startDefuse(second, bomb), "bomb free again after the death");
                helper.assertValueEqual(bomb.getDefuserId(), second.getUUID(), "second defuser takes over");
                helper.assertValueEqual(bomb.getDefuseTicks(), 0, "takeover starts from zero");
                EquipmentSystem.cancel(second);
                helper.assertTrue(bomb.getDefuserId() == null, "cancel releases the bomb");
                helper.succeed();
            } finally {
                lease.active = false;
                bombs(helper).forEach(Entity::discard);
                remove(first);
                remove(second);
                remove(owner);
            }
        });
    }

    /** Heartbeat spam cannot speed a defuse; a saved defuse reloads its state but resets without a live session. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 80)
    public void defuseSpamDoesNotAccelerateAndReloadResets(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_spam_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        PlantedC4Entity original = plant(helper, owner);
        ServerPlayer defuser = defuser(helper, "eq_spam", new Vec3(3.5, 1.0, 2.0), original);
        Lease lease = new Lease(helper, defuser, original);
        lease.send();
        boolean[] spamming = {true};
        helper.onEachTick(() -> {
            if (spamming[0]) {
                int replay = lease.sequence;
                for (int i = 0; i < 4; i++) {
                    lease.send();
                }
                EquipmentSystem.handle(defuser, action("start", lease.bomb.getId(), replay));
                EquipmentSystem.startDefuse(defuser, lease.bomb);
            }
        });
        PlantedC4Entity[] copy = {null};
        int[] savedTicks = {0};
        int[] savedFuse = {0};
        helper.runAfterDelay(30, () -> {
            int ticks = original.getDefuseTicks();
            helper.assertTrue(ticks >= 30 - 2 * MARGIN && ticks <= 30 + 1, "one defuse tick per game tick: " + ticks);
            spamming[0] = false;
            lease.active = false;
            CompoundTag tag = new CompoundTag();
            original.saveWithoutId(tag);
            savedTicks[0] = ticks;
            savedFuse[0] = original.getFuseTicks();
            original.discard();
            PlantedC4Entity loaded = EquipmentRegistry.PLANTED_C4.create(helper.getLevel());
            helper.assertTrue(loaded != null, "entity type creates");
            loaded.load(tag);
            loaded.setUUID(UUID.randomUUID());
            helper.assertValueEqual(loaded.getDefuserId(), defuser.getUUID(), "saved defuser");
            helper.assertValueEqual(loaded.getDefuseTicks(), ticks, "saved defuse progress");
            helper.assertValueEqual(loaded.getFuseTicks(), savedFuse[0], "saved fuse");
            helper.assertValueEqual(loaded.getOwnerId(), owner.getUUID(), "saved owner");
            helper.getLevel().addFreshEntity(loaded);
            copy[0] = loaded;
        });
        helper.runAfterDelay(30 + MARGIN, () -> {
            PlantedC4Entity loaded = copy[0];
            helper.assertTrue(!loaded.isRemoved() && !loaded.isDefused(), "reloaded bomb lives");
            helper.assertValueEqual(loaded.getDefuseTicks(), 0, "no live session for the reloaded bomb: reset");
            helper.assertTrue(loaded.getDefuserId() == null, "reloaded defuser cleared");
            helper.assertTrue(loaded.getFuseTicks() < savedFuse[0], "reloaded fuse keeps running");
            aim(defuser, loaded);
            lease.bomb = loaded;
            lease.active = true;
            lease.send();
            helper.assertValueEqual(loaded.getDefuserId(), defuser.getUUID(), "fresh heartbeat restarts on the reloaded bomb");
            helper.assertValueEqual(loaded.getDefuseTicks(), 0, "restart from zero");
        });
        helper.runAfterDelay(30 + 4 * MARGIN, () -> {
            try {
                int ticks = copy[0].getDefuseTicks();
                helper.assertTrue(ticks > 0 && ticks < savedTicks[0], "restarted progress below the saved one: " + ticks);
                helper.succeed();
            } finally {
                lease.active = false;
                bombs(helper).forEach(Entity::discard);
                remove(defuser);
                remove(owner);
            }
        });
    }

    /**
     * The defuse aim box is forgiving, but sight must reach the bomb itself. A thin panel (vertical open trapdoor;
     * closed door) wholly in front of the bomb body blocks starting and progressing a defuse, even where the
     * inflated aim box protrudes in front of the panel and even with the eye inside the aim box. With the cover
     * removed, an aim that hits only the inflated box (misses the body) still defuses.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 80)
    public void defuseNeedsLineOfSightToTheBombBehindThinCover(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "eq_los_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        BlockState trapdoor = thinPanel(helper, facing -> Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.FACING, facing).setValue(TrapDoorBlock.OPEN, true));
        BlockState door = thinPanel(helper, facing -> Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing).setValue(DoorBlock.OPEN, false));
        // Vertical trapdoor floating at bomb height; a closed door whose upper half covers its bomb.
        helper.setBlock(new BlockPos(1, 2, 4), trapdoor);
        helper.setBlock(new BlockPos(5, 1, 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(5, 2, 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        List<CoverLane> lanes = List.of(
                coverLane(helper, owner, "eq_los_trap", 1, trapdoor),
                coverLane(helper, owner, "eq_los_door", 5, door));
        int uncovered = 3 * HEARTBEAT_TICKS;
        helper.runAfterDelay(uncovered, () -> {
            for (CoverLane lane : lanes) {
                assertReset(helper, lane.bomb(), lane.defuser(), lane.name() + ": heartbeats behind cover");
                helper.assertTrue(!EquipmentSystem.isDefusing(lane.defuser(), lane.bomb()), lane.name() + ": no session");
            }
            helper.setBlock(new BlockPos(1, 2, 4), Blocks.AIR);
            helper.setBlock(new BlockPos(5, 2, 4), Blocks.AIR);
            helper.setBlock(new BlockPos(5, 1, 4), Blocks.AIR);
            for (CoverLane lane : lanes) {
                ServerPlayer defuser = lane.defuser();
                aimAt(defuser, lane.tolerantAim());
                Vec3 eye = defuser.getEyePosition();
                Vec3 end = eye.add(defuser.getViewVector(1.0F).scale(defuser.entityInteractionRange()));
                helper.assertTrue(lane.bomb().getBoundingBox().clip(eye, end).isEmpty()
                        && lane.bomb().getBoundingBox().inflate(PlantedC4Entity.AIM_INFLATE).clip(eye, end).isPresent(),
                        lane.name() + ": fixture aim hits only the forgiving aim box");
                helper.assertTrue(lane.bomb().canDefuse(defuser), lane.name() + ": clear sight keeps the aim tolerance");
                lane.lease().send();
                helper.assertValueEqual(lane.bomb().getDefuserId(), defuser.getUUID(), lane.name() + ": defuse starts");
            }
        });
        helper.runAfterDelay(uncovered + 20, () -> {
            try {
                for (CoverLane lane : lanes) {
                    helper.assertTrue(EquipmentSystem.isDefusing(lane.defuser(), lane.bomb()), lane.name() + ": session active");
                    int ticks = lane.bomb().getDefuseTicks();
                    helper.assertTrue(ticks >= 20 - HEARTBEAT_TICKS - MARGIN, lane.name() + ": defuse progresses: " + ticks);
                }
                helper.succeed();
            } finally {
                for (CoverLane lane : lanes) {
                    lane.lease().active = false;
                    remove(lane.defuser());
                }
                bombs(helper).forEach(Entity::discard);
                remove(owner);
            }
        });
    }

    /**
     * A client alternating fresh start/cancel actions many times per tick cannot flood observers: each actor causes
     * at most one start and one cancel transition event per tick, with nothing backlogged into later ticks. Each
     * cancel still stops the use or defuse at once; on the next tick a legitimate start is accepted and defuse lease
     * renewals keep the clock running.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void startCancelSpamBoundsTransitionEventsPerTick(GameTestHelper helper) {
        floor(helper);
        Observer observer = observer(helper, "eq_flip_hs", new Vec3(1.5, 1.0, 1.5));
        ServerPlayer user = observer.player();
        holdInSlot(user, 0, new ItemStack(EquipmentRegistry.HEALTHSHOT, 3));
        user.setHealth(4.0F);
        ServerPlayer owner = player(helper, "eq_flip_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        PlantedC4Entity bomb = plant(helper, owner);
        ServerPlayer defuser = defuser(helper, "eq_flip_def", new Vec3(3.5, 1.0, 2.0), bomb);
        observer.drain();
        int spamTicks = 10;
        int pairs = 8;
        int restart = spamTicks + 1;
        int end = restart + 2 * EquipmentSystem.DEFUSE_LEASE_TICKS + HEARTBEAT_TICKS;
        int[] tick = {0};
        int[] sequences = {0, 0};
        int[] quiet = {0, 0};
        long[] restartedAt = {0L};
        helper.onEachTick(() -> {
            int t = ++tick[0];
            if (t > end) {
                return;
            }
            List<EquipmentEventPayload> sent = observer.drain();
            int userTransitions = transitions(sent, user.getId(), "start", "cancel");
            int bombTransitions = transitions(sent, bomb.getId(), "disarm_start", "cancel");
            helper.assertTrue(userTransitions <= 2, "tick " + t + ": healthshot transitions " + userTransitions);
            helper.assertTrue(bombTransitions <= 2, "tick " + t + ": defuse transitions " + bombTransitions);
            if (t > restart) {
                quiet[0] += userTransitions;
                quiet[1] += bombTransitions;
            }
            if (t <= spamTicks) {
                for (int i = 0; i < pairs; i++) {
                    EquipmentSystem.handle(user, action("start", -1, ++sequences[0]));
                    EquipmentSystem.handle(user, action("cancel", -1, ++sequences[0]));
                    helper.assertTrue(!user.isUsingItem(), "tick " + t + ": cancel stops the use at once");
                    EquipmentSystem.handle(defuser, action("start", bomb.getId(), ++sequences[1]));
                    EquipmentSystem.handle(defuser, action("cancel", bomb.getId(), ++sequences[1]));
                    helper.assertTrue(bomb.getDefuserId() == null && bomb.getDefuseTicks() == 0
                            && !EquipmentSystem.isDefusing(defuser, bomb), "tick " + t + ": cancel stops the defuse at once");
                }
            } else if (t == restart) {
                EquipmentSystem.handle(user, action("start", -1, ++sequences[0]));
                helper.assertTrue(user.isUsingItem(), "next-tick start after the spam is accepted");
                EquipmentSystem.handle(defuser, action("start", bomb.getId(), ++sequences[1]));
                helper.assertValueEqual(bomb.getDefuserId(), defuser.getUUID(), "next-tick defuse start is accepted");
                helper.assertTrue(EquipmentSystem.isDefusing(defuser, bomb), "defuse session active");
                restartedAt[0] = helper.getLevel().getGameTime();
            } else if (t < end) {
                if ((t - restart) % HEARTBEAT_TICKS == 0) {
                    EquipmentSystem.handle(defuser, action("start", bomb.getId(), ++sequences[1])); // lease renewal
                }
            } else {
                try {
                    helper.assertTrue(quiet[0] <= 1, "no backlog: healthshot transitions after the spam " + quiet[0]);
                    helper.assertTrue(quiet[1] <= 1, "no backlog: defuse transitions after the spam " + quiet[1]);
                    helper.assertTrue(user.isUsingItem(), "legitimate use survives");
                    helper.assertValueEqual(user.getHealth(), 4.0F, "spam healed nothing");
                    helper.assertValueEqual(user.getMainHandItem().getCount(), 3, "spam consumed nothing");
                    helper.assertTrue(EquipmentSystem.isDefusing(defuser, bomb), "renewed lease keeps the session");
                    long elapsed = helper.getLevel().getGameTime() - restartedAt[0];
                    int ticks = bomb.getDefuseTicks();
                    helper.assertTrue(ticks >= elapsed - MARGIN && ticks <= elapsed + 1,
                            "renewals never restart the defuse clock: " + ticks + " over " + elapsed);
                    helper.succeed();
                } finally {
                    bombs(helper).forEach(Entity::discard);
                    remove(user);
                    remove(defuser);
                    remove(owner);
                }
            }
        });
    }

    /**
     * Two players holding sessions on the same bomb cannot trade it back and forth within one tick to flood
     * observers. The fixture proves one valid takeover (owner looks away, the other aims and sends a fresh start)
     * that leaves the displaced player holding a same-bomb session while re-aimed. Then, many times per tick, each
     * player in turn aims while the other looks away and sends a fresh start. Whoever ends up owning the bomb, at
     * most a few defuse transition events go out per tick, every change of defuser restarts from zero, explicit
     * cancels release the bomb at once, and on the next tick a legitimate start is accepted and keeps its clock.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void alternatingDefuseTakeoversBoundTransitions(GameTestHelper helper) {
        floor(helper);
        Observer observer = observer(helper, "eq_alt_obs", new Vec3(1.5, 1.0, 1.5));
        ServerPlayer owner = player(helper, "eq_alt_own", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        PlantedC4Entity bomb = plant(helper, owner);
        ServerPlayer a = defuser(helper, "eq_alt_a", new Vec3(3.5, 1.0, 2.0), bomb);
        ServerPlayer b = defuser(helper, "eq_alt_b", new Vec3(3.5, 1.0, 5.0), bomb);
        int[] sequences = {0, 0};

        // Fixture: one real takeover, leaving the displaced player with a retained same-bomb session.
        EquipmentSystem.handle(a, action("start", bomb.getId(), ++sequences[0]));
        helper.assertValueEqual(bomb.getDefuserId(), a.getUUID(), "fixture: first player defuses");
        look(a, 0.0F, -90.0F);
        helper.assertTrue(!bomb.canDefuse(a), "fixture: looking up loses the aim");
        EquipmentSystem.handle(b, action("start", bomb.getId(), ++sequences[1]));
        helper.assertValueEqual(bomb.getDefuserId(), b.getUUID(), "fixture: second player takes over");
        helper.assertValueEqual(bomb.getDefuseTicks(), 0, "fixture: takeover starts from zero");
        helper.assertTrue(EquipmentSystem.isDefusing(b, bomb), "fixture: new owner has a session");
        helper.assertTrue(EquipmentSystem.isDefusing(a, bomb), "fixture: displaced player retains a same-bomb session");
        aim(a, bomb);
        helper.assertTrue(bomb.canDefuse(a), "fixture: displaced player aims again");
        helper.assertValueEqual(bomb.getDefuserId(), b.getUUID(), "fixture: re-aiming alone does not take the bomb");
        observer.drain();

        int spamTicks = 10;
        int cycles = 8;
        int restart = spamTicks + 1;
        int resume = restart + 1;
        int end = resume + 2 * EquipmentSystem.DEFUSE_LEASE_TICKS + HEARTBEAT_TICKS;
        int[] tick = {0};
        int[] quiet = {0};
        long[] restartedAt = {0L};
        helper.onEachTick(() -> {
            int t = ++tick[0];
            if (t > end) {
                return;
            }
            int transitions = transitions(observer.drain(), bomb.getId(), "disarm_start", "cancel");
            helper.assertTrue(transitions <= 6, "tick " + t + ": defuse transitions from both players " + transitions);
            if (t > resume) {
                quiet[0] += transitions;
            }
            if (t <= spamTicks) {
                for (int i = 0; i < cycles; i++) {
                    for (int turn = 0; turn < 2; turn++) {
                        ServerPlayer taker = turn == 0 ? a : b;
                        ServerPlayer other = turn == 0 ? b : a;
                        UUID before = bomb.getDefuserId();
                        aim(taker, bomb);
                        look(other, 0.0F, -90.0F);
                        EquipmentSystem.handle(taker, action("start", bomb.getId(), ++sequences[turn]));
                        UUID after = bomb.getDefuserId();
                        helper.assertTrue(!bomb.isRemoved() && !bomb.isDefused(), "tick " + t + ": bomb still live");
                        if (after != null && !after.equals(before)) {
                            helper.assertValueEqual(bomb.getDefuseTicks(), 0, "tick " + t + ": a new defuser starts from zero");
                        }
                    }
                }
                aim(a, bomb);
                aim(b, bomb);
            } else if (t == restart) {
                EquipmentSystem.handle(a, action("cancel", bomb.getId(), ++sequences[0]));
                EquipmentSystem.handle(b, action("cancel", bomb.getId(), ++sequences[1]));
                helper.assertTrue(bomb.getDefuserId() == null && bomb.getDefuseTicks() == 0,
                        "explicit cancels release the bomb at once");
                helper.assertTrue(!EquipmentSystem.isDefusing(a, bomb) && !EquipmentSystem.isDefusing(b, bomb),
                        "explicit cancels end both sessions");
            } else if (t == resume) {
                EquipmentSystem.handle(a, action("start", bomb.getId(), ++sequences[0]));
                helper.assertValueEqual(bomb.getDefuserId(), a.getUUID(), "next-tick defuse start is accepted");
                helper.assertValueEqual(bomb.getDefuseTicks(), 0, "next-tick defuse starts from zero");
                helper.assertTrue(EquipmentSystem.isDefusing(a, bomb), "defuse session active");
                EquipmentSystem.handle(b, action("start", bomb.getId(), ++sequences[1]));
                helper.assertValueEqual(bomb.getDefuserId(), a.getUUID(), "second start refused while the first is active");
                helper.assertTrue(!EquipmentSystem.isDefusing(b, bomb), "refused player has no session");
                restartedAt[0] = helper.getLevel().getGameTime();
            } else if (t < end) {
                if ((t - resume) % HEARTBEAT_TICKS == 0) {
                    EquipmentSystem.handle(a, action("start", bomb.getId(), ++sequences[0])); // lease renewal
                }
            } else {
                try {
                    helper.assertTrue(quiet[0] <= 1, "no backlog: defuse transitions after the restart " + quiet[0]);
                    helper.assertValueEqual(bomb.getDefuserId(), a.getUUID(), "legitimate defuser keeps the bomb");
                    helper.assertTrue(EquipmentSystem.isDefusing(a, bomb), "renewed lease keeps the session");
                    long elapsed = helper.getLevel().getGameTime() - restartedAt[0];
                    int ticks = bomb.getDefuseTicks();
                    helper.assertTrue(ticks >= elapsed - MARGIN && ticks <= elapsed + 1,
                            "renewals never restart the defuse clock: " + ticks + " over " + elapsed);
                    helper.succeed();
                } finally {
                    bombs(helper).forEach(Entity::discard);
                    remove(observer.player());
                    remove(a);
                    remove(b);
                    remove(owner);
                }
            }
        });
    }

    // ---------------------------------------------------------------- Helpers

    /** The client's defuse lease: a fresh-sequence "start" for the bomb every {@link #HEARTBEAT_TICKS} while active. */
    private static final class Lease {
        final ServerPlayer player;
        PlantedC4Entity bomb;
        boolean active = true;
        int sequence;

        Lease(GameTestHelper helper, ServerPlayer player, PlantedC4Entity bomb) {
            this.player = player;
            this.bomb = bomb;
            helper.onEachTick(() -> {
                if (active && helper.getTick() % HEARTBEAT_TICKS == 0) {
                    send();
                }
            });
        }

        void send() {
            EquipmentSystem.handle(player, action("start", bomb.getId(), ++sequence));
        }

        void cancel() {
            EquipmentSystem.handle(player, action("cancel", bomb.getId(), ++sequence));
        }
    }

    /** Two cover lanes share this shape: bomb wholly behind a thin panel at cell (x, 2, 4), defuser in front. */
    private record CoverLane(String name, PlantedC4Entity bomb, ServerPlayer defuser, Lease lease, Vec3 tolerantAim) {}

    /**
     * Plants a bomb 0.02 behind the panel's far face (so its aim box protrudes in front of the panel) and checks
     * that a defuser in front is refused both aiming through the protruding aim box and with its eye inside it.
     * Leaves the defuser aiming at the bomb centre with a running lease.
     */
    private static CoverLane coverLane(GameTestHelper helper, ServerPlayer owner, String name, int x, BlockState panel) {
        AABB shape = panel.getCollisionShape(helper.getLevel(), BlockPos.ZERO).bounds();
        Vec3 cell = helper.absoluteVec(new Vec3(x, 2.0, 4.0));
        double panelNear = cell.z + shape.minZ;
        double panelFar = cell.z + shape.maxZ;
        double half = EquipmentRegistry.PLANTED_C4.getWidth() / 2.0;
        PlantedC4Entity bomb = PlantedC4Entity.plant(helper.getLevel(), owner,
                new Vec3(cell.x + 0.5, cell.y + 0.42, panelFar + 0.02 + half));
        helper.assertTrue(bomb != null, name + ": bomb planted behind the panel");
        AABB body = bomb.getBoundingBox();
        AABB aimBox = body.inflate(PlantedC4Entity.AIM_INFLATE);
        helper.assertTrue(body.minZ > panelFar, name + ": fixture: body wholly behind the panel");
        helper.assertTrue(aimBox.minZ < panelNear, name + ": fixture: aim box protrudes in front of the panel");
        double front = 4.0 + (aimBox.minZ - cell.z);
        Vec3 far = new Vec3(x + 0.5, 1.0, front - 1.0);
        ServerPlayer defuser = player(helper, name, GameType.SURVIVAL, far);
        defuser.getInventory().clearContent();
        defuser.getInventory().selected = 0;
        Vec3 centre = body.getCenter();
        Vec3 side = new Vec3(body.maxX + 0.1, centre.y, centre.z);
        aimAt(defuser, centre);
        assertCovered(helper, name + " centre aim", bomb, defuser, panelNear);
        aimAt(defuser, side);
        assertCovered(helper, name + " off-body aim", bomb, defuser, panelNear);

        place(helper, defuser, new Vec3(x + 0.5, 1.0, front + 0.02));
        look(defuser, 0.0F, 0.0F);
        Vec3 eye = defuser.getEyePosition();
        helper.assertTrue(aimBox.contains(eye) && eye.z < panelNear, name + ": fixture: eye in the aim box, before the panel");
        helper.assertTrue(!bomb.canDefuse(defuser), name + ": eye inside the aim box does not see through the panel");
        helper.assertTrue(!EquipmentSystem.startDefuse(defuser, bomb), name + ": eye-inside defuse refused");
        helper.assertTrue(bomb.getDefuserId() == null, name + ": eye-inside defuse not started");

        place(helper, defuser, far);
        aimAt(defuser, centre);
        Lease lease = new Lease(helper, defuser, bomb);
        lease.send();
        helper.assertTrue(bomb.getDefuserId() == null, name + ": fresh start behind the panel refused");
        return new CoverLane(name, bomb, defuser, lease, side);
    }

    private static void assertCovered(GameTestHelper helper, String why, PlantedC4Entity bomb, ServerPlayer player,
            double panelNear) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
        Vec3 aimHit = bomb.getBoundingBox().inflate(PlantedC4Entity.AIM_INFLATE).clip(eye, end).orElse(null);
        helper.assertTrue(aimHit != null && aimHit.z < panelNear, why + ": fixture: ray meets the aim box before the panel");
        helper.assertTrue(!bomb.canDefuse(player), why + ": panel blocks the defuse");
        helper.assertTrue(!EquipmentSystem.startDefuse(player, bomb), why + ": defuse refused");
        helper.assertTrue(bomb.getDefuserId() == null, why + ": defuse not started");
    }

    /** The facing whose collision panel is full-width along x, at the north (min z) side, thinner than the aim inflate. */
    private static BlockState thinPanel(GameTestHelper helper, Function<Direction, BlockState> facing) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState state = facing.apply(direction);
            AABB b = state.getCollisionShape(helper.getLevel(), BlockPos.ZERO).bounds();
            if (b.minX == 0.0 && b.maxX == 1.0 && b.minY == 0.0 && b.maxY == 1.0 && b.minZ == 0.0
                    && b.maxZ < PlantedC4Entity.AIM_INFLATE - 0.03) {
                return state;
            }
        }
        throw new IllegalStateException("no thin north panel facing for " + facing.apply(Direction.NORTH));
    }

    private static int transitions(List<EquipmentEventPayload> sent, int entityId, String... events) {
        List<String> wanted = List.of(events);
        int count = 0;
        for (EquipmentEventPayload payload : sent) {
            if (payload.entityId() == entityId && wanted.contains(payload.event())) {
                count++;
            }
        }
        return count;
    }

    /** A connected player whose client accepts equipment events; {@link #drain} returns those sent so far. */
    private record Observer(ServerPlayer player, EmbeddedChannel channel) {
        List<EquipmentEventPayload> drain() {
            List<EquipmentEventPayload> sent = new ArrayList<>();
            Object message;
            while ((message = channel.readOutbound()) != null) {
                if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload() instanceof EquipmentEventPayload payload) {
                    sent.add(payload);
                }
            }
            return sent;
        }
    }

    /** Registers the equipment event channel as a csmc client's channel registration would. */
    @SuppressWarnings("unchecked")
    private static Observer observer(GameTestHelper helper, String name, Vec3 relative) {
        Observer observer = connect(helper, name, GameType.SURVIVAL, relative);
        try {
            Field field = AbstractChanneledNetworkAddon.class.getDeclaredField("sendableChannels");
            field.setAccessible(true);
            ((Set<ResourceLocation>) field.get(ServerNetworkingImpl.getAddon(observer.player().connection)))
                    .add(EquipmentEventPayload.TYPE.id());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        observer.drain();
        return observer;
    }

    /** UUIDs present in the bomb's non-default synced data (an {@code Optional<UUID>} defuser once set). */
    private static List<UUID> syncedUuids(PlantedC4Entity bomb) {
        List<UUID> uuids = new ArrayList<>();
        List<SynchedEntityData.DataValue<?>> values = bomb.getEntityData().getNonDefaultValues();
        if (values != null) {
            for (SynchedEntityData.DataValue<?> value : values) {
                if (value.value() instanceof Optional<?> optional && optional.orElse(null) instanceof UUID uuid) {
                    uuids.add(uuid);
                }
            }
        }
        return uuids;
    }

    private static void assertReset(GameTestHelper helper, PlantedC4Entity bomb, ServerPlayer defuser, String why) {
        helper.assertTrue(!bomb.isRemoved() && !bomb.isDefused(), why + ": bomb still live");
        helper.assertValueEqual(bomb.getDefuseTicks(), 0, why + ": progress reset");
        helper.assertTrue(bomb.getDefuseProgress() == 0.0F, why + ": synced progress reset");
        helper.assertTrue(bomb.getDefuserId() == null, why + ": defuser cleared");
    }

    /** Restarted by a heartbeat at most {@link #HEARTBEAT_TICKS} after the reset {@code since} ticks ago. */
    private static void assertRestarted(GameTestHelper helper, PlantedC4Entity bomb, ServerPlayer defuser, int since, String why) {
        helper.assertValueEqual(bomb.getDefuserId(), defuser.getUUID(), why + ": defusing again");
        helper.assertTrue(EquipmentSystem.isDefusing(defuser, bomb), why + ": session active");
        int ticks = bomb.getDefuseTicks();
        helper.assertTrue(ticks >= since - HEARTBEAT_TICKS - MARGIN && ticks <= since,
                why + ": restarted from zero, got " + ticks + " within " + since);
    }

    private static EquipmentActionPayload action(String action, int entityId, int sequence) {
        return new EquipmentActionPayload(action, entityId, sequence);
    }

    private static void refusedPlant(GameTestHelper helper, ServerPlayer player, String why) {
        helper.assertTrue(EquipmentSystem.plantingTarget(player) == null, why + ": no planting target");
        helper.assertTrue(!EquipmentSystem.startUse(player, InteractionHand.MAIN_HAND), why + ": plant refused");
        helper.assertTrue(!player.isUsingItem(), why + ": no use started");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, why + ": C4 kept");
    }

    private static PlantedC4Entity plant(GameTestHelper helper, ServerPlayer owner) {
        PlantedC4Entity bomb = PlantedC4Entity.plant(helper.getLevel(), owner, helper.absoluteVec(BOMB));
        helper.assertTrue(bomb != null, "bomb planted");
        return bomb;
    }

    /** A survival player with an empty selected slot 0, aiming at the bomb from {@code relative}. */
    private static ServerPlayer defuser(GameTestHelper helper, String name, Vec3 relative, PlantedC4Entity bomb) {
        ServerPlayer player = player(helper, name, GameType.SURVIVAL, relative);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        aim(player, bomb);
        helper.assertTrue(bomb.canDefuse(player), "fixture aims at the bomb: " + name);
        return player;
    }

    private static void aim(ServerPlayer player, PlantedC4Entity bomb) {
        aimAt(player, bomb.getBoundingBox().getCenter());
    }

    private static void aimAt(ServerPlayer player, Vec3 point) {
        Vec3 d = point.subtract(player.getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
        look(player, yaw, pitch);
    }

    private static void look(ServerPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
    }

    private static void holdInSlot(ServerPlayer player, int slot, ItemStack stack) {
        player.getInventory().setItem(slot, stack);
        player.getInventory().selected = slot;
    }

    /** Explosion damage to players is zero on peaceful; spawn invulnerability must also run out. */
    private static void combatReady(MinecraftServer server, Difficulty difficulty, ServerPlayer... players) {
        if (difficulty == Difficulty.PEACEFUL) {
            server.setDifficulty(Difficulty.NORMAL, true);
        }
        for (ServerPlayer player : players) {
            tickPlayer(player, 61);
            player.setHealth(player.getMaxHealth());
        }
    }

    private static void restoreDifficulty(MinecraftServer server, Difficulty difficulty) {
        if (server.getWorldData().getDifficulty() != difficulty) {
            server.setDifficulty(difficulty, true);
        }
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** Advances only the player's item cooldown clock (mock players are not ticked by a connection). */
    private static void tickCooldowns(ServerPlayer player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.getCooldowns().tick();
        }
    }

    /** Server-side player tick (spawn immunity counts down here; mock players are not ticked). */
    private static void tickPlayer(ServerPlayer player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.tick();
        }
    }

    private static Husk husk(GameTestHelper helper, Vec3 relative) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, relative);
        husk.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HUSK_HEALTH);
        husk.setHealth(HUSK_HEALTH);
        husk.setNoGravity(true);
        return husk;
    }

    private static List<PlantedC4Entity> bombs(GameTestHelper helper) {
        AABB area = new AABB(helper.absoluteVec(new Vec3(-1.0, -1.0, -1.0)), helper.absoluteVec(new Vec3(9.0, 9.0, 9.0)));
        return helper.getLevel().getEntitiesOfClass(PlantedC4Entity.class, area, Entity::isAlive);
    }

    private static void place(GameTestHelper helper, ServerPlayer player, Vec3 relative) {
        Vec3 position = helper.absoluteVec(relative);
        player.teleportTo(helper.getLevel(), position.x, position.y, position.z, player.getYRot(), player.getXRot());
    }

    /** Mock player with its own profile name (scoreboard teams key on names), facing +X. */
    private static ServerPlayer player(GameTestHelper helper, String name, GameType mode, Vec3 relative) {
        return connect(helper, name, mode, relative).player();
    }

    private static Observer connect(GameTestHelper helper, String name, GameType mode, Vec3 relative) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(mode);
        Vec3 position = helper.absoluteVec(relative);
        player.teleportTo(level, position.x, position.y, position.z, -90.0F, 0.0F);
        look(player, -90.0F, 0.0F);
        player.setNoGravity(true);
        return new Observer(player, channel);
    }

    private static void remove(ServerPlayer player) {
        EquipmentSystem.forget(player);
        if (!player.hasDisconnected() && player.server.getPlayerList().getPlayer(player.getUUID()) != null) {
            player.server.getPlayerList().remove(player);
        }
        player.discard();
    }
}
