package dev.csmc.grenade;

import com.mojang.authlib.GameProfile;
import dev.csmc.CsmcMod;
import dev.csmc.ballistics.BlockBreakPolicy;
import dev.csmc.data.MaterialTable;
import dev.csmc.item.GrenadeItem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

/**
 * Real-entity grenade GameTests. Every test lays its own stone floor at relative y = 0 inside the empty
 * 8x8x8 structure and uses real projectiles, clouds, husks (no AI, no sun burning) and mock players with
 * distinct profiles. Global state (the {@code csmcBlockBreaking} gamerule, server PvP, scoreboard teams) is
 * only changed in dedicated batches and restored in {@code finally}.
 */
public final class GrenadeGameTests implements FabricGameTest {
    private static final double EPS = 1.0E-6;

    // ---------------------------------------------------------------- Registry and throwing

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void sixGrenadesRegistered(GameTestHelper helper) {
        List<String> ids = List.of("hegrenade", "flashbang", "smokegrenade", "molotov", "incgrenade", "decoy");
        helper.assertValueEqual(List.copyOf(GrenadeRegistry.ITEMS.keySet()), ids, "grenade ids in kind order");
        for (GrenadeKind kind : GrenadeKind.values()) {
            GrenadeItem item = GrenadeRegistry.ITEMS.get(kind.id());
            helper.assertValueEqual(item.kind(), kind, "item kind " + kind);
            helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(item), CsmcMod.id(kind.id()), "item id " + kind);
            helper.assertValueEqual(GrenadeKind.byId(kind.id()), kind, "byId " + kind);
        }
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(GrenadeRegistry.PROJECTILE), CsmcMod.id("grenade"), "projectile id");
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(GrenadeRegistry.CLOUD), CsmcMod.id("grenade_cloud"), "cloud id");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void throwConsumesOneInSurvivalNotCreativeWithCooldown(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer survival = player(helper, "gs_surv", GameType.SURVIVAL, new Vec3(1.5, 1.0, 2.5));
        ServerPlayer creative = player(helper, "gs_crea", GameType.CREATIVE, new Vec3(1.5, 1.0, 5.5));
        try {
            ItemStack stack = new ItemStack(GrenadeRegistry.item(GrenadeKind.DECOY), 3);
            survival.setItemInHand(InteractionHand.MAIN_HAND, stack);
            survival.gameMode.useItem(survival, level, stack, InteractionHand.MAIN_HAND);
            helper.assertValueEqual(projectiles(helper).size(), 0, "plain item use no longer throws");
            helper.assertValueEqual(survival.getMainHandItem().getCount(), 3, "plain item use consumes nothing");

            GrenadeProjectile grenade = GrenadeThrow.handle(survival, request(GrenadeThrow.Strength.OVERHAND, 1));
            helper.assertTrue(grenade != null, "survival throw accepted");
            helper.assertValueEqual(survival.getMainHandItem().getCount(), 2, "survival consumes exactly one");
            helper.assertValueEqual(projectiles(helper).size(), 1, "one server projectile");
            helper.assertValueEqual(grenade.getKind(), GrenadeKind.DECOY, "thrown kind");
            helper.assertValueEqual(grenade.getOwnerId(), survival.getUUID(), "owner");
            assertVec(helper, grenade.getDeltaMovement(), GrenadeThrow.velocity(0.0F, -90.0F, Vec3.ZERO, 1.0), "server velocity");
            helper.assertTrue(grenade.getDeltaMovement().x > 2.0, "thrown along the view (+X)");

            helper.assertTrue(GrenadeThrow.handle(survival, request(GrenadeThrow.Strength.OVERHAND, 2)) == null,
                    "cooldown blocks the immediate second throw");
            helper.assertValueEqual(survival.getMainHandItem().getCount(), 2, "cooldown consumes nothing");
            helper.assertValueEqual(projectiles(helper).size(), 1, "cooldown spawns nothing");
            helper.assertTrue(survival.getCooldowns().isOnCooldown(GrenadeRegistry.item(GrenadeKind.HE)),
                    "cooldown is shared by all grenade items");

            ItemStack creativeStack = new ItemStack(GrenadeRegistry.item(GrenadeKind.SMOKE), 3);
            creative.setItemInHand(InteractionHand.MAIN_HAND, creativeStack);
            helper.assertTrue(GrenadeThrow.handle(creative, request(GrenadeThrow.Strength.LOB, 1)) != null,
                    "creative throw accepted");
            helper.assertValueEqual(creative.getMainHandItem().getCount(), 3, "creative keeps the stack");
            helper.assertValueEqual(projectiles(helper).size(), 2, "creative throw spawned exactly one more");
        } finally {
            projectiles(helper).forEach(Entity::discard);
            remove(survival);
            remove(creative);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void throwStrengthScalesSpeedAndSource(GameTestHelper helper) {
        double full = 675.0 / 16.0 / 20.0;
        helper.assertTrue(Math.abs(GrenadeThrow.speed(1.0) - full) < EPS, "overhand 675 u/s");
        helper.assertTrue(Math.abs(GrenadeThrow.speed(0.5) - full * 0.65) < EPS, "medium 65%");
        helper.assertTrue(Math.abs(GrenadeThrow.speed(0.0) - full * 0.3) < EPS, "lob 30%");
        helper.assertValueEqual(GrenadeThrow.Strength.of(true, false), GrenadeThrow.Strength.OVERHAND, "attack only");
        helper.assertValueEqual(GrenadeThrow.Strength.of(false, true), GrenadeThrow.Strength.LOB, "use only");
        helper.assertValueEqual(GrenadeThrow.Strength.of(true, true), GrenadeThrow.Strength.MEDIUM, "both");
        floor(helper);
        GrenadeThrow.Strength[] strengths = GrenadeThrow.Strength.values();
        ServerPlayer[] players = new ServerPlayer[strengths.length];
        try {
            for (int i = 0; i < strengths.length; i++) {
                players[i] = player(helper, "gs_str" + i, GameType.SURVIVAL, new Vec3(0.5, 1.0, 1.5 + 2 * i));
                players[i].setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GrenadeRegistry.item(GrenadeKind.SMOKE), 2));
                double strength = strengths[i].value;
                GrenadeProjectile grenade = GrenadeThrow.handle(players[i], request(strengths[i], 7));
                helper.assertTrue(grenade != null, strengths[i] + " accepted");
                helper.assertTrue(Math.abs(grenade.getDeltaMovement().length() - GrenadeThrow.speed(strength)) < 1.0E-6,
                        strengths[i] + " speed " + grenade.getDeltaMovement().length());
                Vec3 eye = players[i].getEyePosition();
                Vec3 expected = eye.add(0.0, (strength * 12.0 - 12.0) / 16.0 - GrenadeTuning.PROJECTILE_SIZE / 2.0, 0.0)
                        .add(GrenadeThrow.direction(0.0F, -90.0F).scale(22.0 / 16.0));
                assertVec(helper, grenade.position(), expected, strengths[i] + " source");
            }
        } finally {
            projectiles(helper).forEach(Entity::discard);
            for (ServerPlayer player : players) {
                if (player != null) remove(player);
            }
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void throwPitchTiltsUpward(GameTestHelper helper) {
        helper.assertTrue(Math.abs(GrenadeThrow.tiltedPitch(0.0F) + 10.0F) < 1.0E-4, "level aim tilts 10 degrees up");
        helper.assertTrue(Math.abs(GrenadeThrow.tiltedPitch(45.0F) - 40.0F) < 1.0E-4, "45 down becomes 40");
        helper.assertTrue(Math.abs(GrenadeThrow.tiltedPitch(-45.0F) + 50.0F) < 1.0E-4, "45 up becomes 50 up");
        helper.assertTrue(Math.abs(GrenadeThrow.tiltedPitch(90.0F) - 90.0F) < 1.0E-4, "straight down unchanged");
        helper.assertTrue(Math.abs(GrenadeThrow.tiltedPitch(-90.0F) + 90.0F) < 1.0E-4, "straight up unchanged");
        floor(helper);
        ServerPlayer player = player(helper, "gs_pitch", GameType.SURVIVAL, new Vec3(1.5, 1.0, 3.5));
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GrenadeRegistry.item(GrenadeKind.SMOKE), 2));
            GrenadeProjectile grenade = GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 1));
            helper.assertTrue(grenade != null, "throw accepted");
            double expectedY = Math.sin(Math.toRadians(10.0)) * GrenadeThrow.speed(1.0);
            helper.assertTrue(Math.abs(grenade.getDeltaMovement().y - expectedY) < 1.0E-4,
                    "level aim launches upward: " + grenade.getDeltaMovement().y);
        } finally {
            projectiles(helper).forEach(Entity::discard);
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void throwInheritsPlayerMomentum(GameTestHelper helper) {
        Vec3 run = new Vec3(0.0, 0.0, 0.2);
        assertVec(helper, GrenadeThrow.velocity(0.0F, -90.0F, run, 1.0),
                GrenadeThrow.velocity(0.0F, -90.0F, Vec3.ZERO, 1.0).add(0.0, 0.0, 0.25), "1.25x momentum");
        floor(helper);
        ServerPlayer grounded = player(helper, "gs_mom_g", GameType.SURVIVAL, new Vec3(1.5, 1.0, 1.5));
        ServerPlayer jumping = player(helper, "gs_mom_j", GameType.SURVIVAL, new Vec3(1.5, 3.0, 5.5));
        try {
            grounded.setOnGround(true);
            grounded.setKnownMovement(new Vec3(0.0, -0.0784, 0.2));
            jumping.setOnGround(false);
            jumping.setKnownMovement(new Vec3(0.1, 0.4, 0.0));
            for (ServerPlayer p : List.of(grounded, jumping)) {
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GrenadeRegistry.item(GrenadeKind.SMOKE), 2));
            }
            GrenadeProjectile walk = GrenadeThrow.handle(grounded, request(GrenadeThrow.Strength.OVERHAND, 1));
            GrenadeProjectile jump = GrenadeThrow.handle(jumping, request(GrenadeThrow.Strength.OVERHAND, 1));
            helper.assertTrue(walk != null && jump != null, "both throws accepted");
            assertVec(helper, walk.getDeltaMovement(), GrenadeThrow.velocity(0.0F, -90.0F, new Vec3(0.0, 0.0, 0.2), 1.0),
                    "running throw carries horizontal momentum, not ground gravity");
            assertVec(helper, jump.getDeltaMovement(), GrenadeThrow.velocity(0.0F, -90.0F, new Vec3(0.1, 0.4, 0.0), 1.0),
                    "jump throw carries vertical momentum");
        } finally {
            projectiles(helper).forEach(Entity::discard);
            remove(grounded);
            remove(jumping);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void throwIntoWallSpawnsOutsideIt(GameTestHelper helper) {
        floor(helper);
        for (int z = 0; z < 8; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(2, y, z), Blocks.STONE);
            }
        }
        ServerPlayer player = player(helper, "gs_wall", GameType.SURVIVAL, new Vec3(1.5, 1.0, 3.5));
        try {
            double face = helper.absoluteVec(new Vec3(2.0, 0.0, 0.0)).x;
            for (GrenadeThrow.Strength strength : GrenadeThrow.Strength.values()) {
                player.getCooldowns().removeCooldown(GrenadeRegistry.item(GrenadeKind.SMOKE));
                for (GrenadeKind kind : GrenadeKind.values()) {
                    player.getCooldowns().removeCooldown(GrenadeRegistry.item(kind));
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GrenadeRegistry.item(GrenadeKind.SMOKE), 2));
                GrenadeProjectile grenade = GrenadeThrow.handle(player, request(strength, strength.ordinal() + 1));
                helper.assertTrue(grenade != null, strength + " accepted");
                helper.assertTrue(grenade.getBoundingBox().maxX <= face + EPS,
                        strength + " spawned outside the wall: maxX " + grenade.getBoundingBox().maxX + " face " + face);
                helper.assertTrue(helper.getLevel().noCollision(grenade, grenade.getBoundingBox()),
                        strength + " spawned in free space");
                grenade.discard();
            }
        } finally {
            projectiles(helper).forEach(Entity::discard);
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void invalidThrowRequestsRejected(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, "gs_bad", GameType.SURVIVAL, new Vec3(1.5, 1.0, 3.5));
        try {
            helper.assertTrue(GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 1)) == null,
                    "empty hand rejected");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.STONE, 4));
            helper.assertTrue(GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 2)) == null,
                    "non-grenade rejected");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GrenadeRegistry.item(GrenadeKind.HE), 4));
            helper.assertTrue(GrenadeThrow.handle(player, new dev.csmc.network.GrenadeThrowPayload(null, 3)) == null,
                    "unknown strength rejected");
            helper.assertTrue(GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 4)) != null,
                    "valid request accepted");
            for (GrenadeKind kind : GrenadeKind.values()) {
                player.getCooldowns().removeCooldown(GrenadeRegistry.item(kind));
            }
            helper.assertTrue(GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 4)) == null,
                    "replayed sequence rejected");
            helper.assertTrue(GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 3)) == null,
                    "older sequence rejected");
            player.setGameMode(GameType.SPECTATOR);
            helper.assertTrue(GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 5)) == null,
                    "spectator rejected");
            player.setGameMode(GameType.SURVIVAL);
            player.setHealth(0.0F);
            helper.assertTrue(GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 6)) == null,
                    "dead player rejected");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "only the valid throw consumed");
            helper.assertValueEqual(projectiles(helper).size(), 1, "only the valid throw spawned");
        } finally {
            projectiles(helper).forEach(Entity::discard);
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void previewArcMatchesServerProjectile(GameTestHelper helper) {
        floor(helper);
        for (int z = 0; z < 8; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
            }
        }
        GrenadeArc.Path path = parity(helper, GrenadeKind.DECOY, new Vec3(1.5, 2.0, 3.5), new Vec3(0.55, 0.35, 0.07));
        helper.assertTrue(path.landed(), "preview comes to rest");
        boolean bouncedWall = false;
        for (int i = 1; i < path.size(); i++) {
            bouncedWall |= path.x(i) < path.x(i - 1);
        }
        helper.assertTrue(bouncedWall, "path covers a wall bounce");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void previewMatchesServerAtShoreline(GameTestHelper helper) {
        floor(helper);
        for (int x = 3; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
            }
        }
        // Rolls along the shore with its box overlapping the water while its centre stays over dry land.
        GrenadeArc.Path path = parity(helper, GrenadeKind.DECOY, new Vec3(2.95, 1.0, 0.5), new Vec3(0.0, 0.0, 0.3));
        helper.assertTrue(path.size() > 2, "path rolls along the shore");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void previewMatchesServerInFlowingWater(GameTestHelper helper) {
        floor(helper);
        // A channel whose water level drops along +X: the current flows downstream.
        for (int x = 1; x < 7; x++) {
            helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 4), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, x));
        }
        Vec3 start = new Vec3(1.5, 1.05, 3.5);
        Vec3 velocity = new Vec3(0.1, 0.0, 0.0);
        GrenadeArc.Path path = parity(helper, GrenadeKind.DECOY, start, velocity);
        GrenadeArc.Collider world = GrenadeArc.collider(helper.getLevel());
        GrenadeArc.Collider still = new GrenadeArc.Collider() {
            @Override
            public Vec3 collide(Vec3 position, Vec3 motion) {
                return world.collide(position, motion);
            }

            @Override
            public GrenadeArc.Fluid fluid(Vec3 position, Vec3 v) {
                return new GrenadeArc.Fluid(world.fluid(position, v).inWater(), v);
            }
        };
        GrenadeArc.Path withoutCurrent = GrenadeArc.simulate(still, GrenadeKind.DECOY, helper.absoluteVec(start), velocity);
        helper.assertTrue(path.end().distanceTo(withoutCurrent.end()) > 1.0E-3, "the current pushes the prediction");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void previewEndsWhereUnderwaterFireIsPutOut(GameTestHelper helper) {
        floor(helper);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
                }
            }
        }
        GrenadeArc.Path path = parity(helper, GrenadeKind.MOLOTOV, new Vec3(1.5, 2.0, 3.5), new Vec3(0.4, 0.1, 0.0));
        helper.assertTrue(path.extinguished() && path.size() == 2, "fire prediction ends after one tick in water");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void crawlingLobSpawnsAboveTheFloor(GameTestHelper helper) {
        floor(helper);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
            }
        }
        ServerPlayer player = player(helper, "gs_crawl", GameType.SURVIVAL, new Vec3(1.5, 1.0, 3.5));
        try {
            player.setPose(Pose.SWIMMING);
            player.refreshDimensions();
            helper.assertTrue(Math.abs(player.getEyeHeight() - 0.4F) < 1.0E-4, "crawling eye height " + player.getEyeHeight());
            double floorTop = helper.absoluteVec(new Vec3(0.0, 1.0, 0.0)).y;
            for (GrenadeThrow.Strength strength : GrenadeThrow.Strength.values()) {
                for (GrenadeKind kind : GrenadeKind.values()) {
                    player.getCooldowns().removeCooldown(GrenadeRegistry.item(kind));
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GrenadeRegistry.item(GrenadeKind.SMOKE), 2));
                GrenadeProjectile grenade = GrenadeThrow.handle(player, request(strength, strength.ordinal() + 1));
                helper.assertTrue(grenade != null, strength + " accepted");
                helper.assertTrue(grenade.getBoundingBox().minY >= floorTop - EPS,
                        strength + " above the floor: minY " + grenade.getBoundingBox().minY + " floor " + floorTop);
                helper.assertTrue(helper.getLevel().noCollision(grenade, grenade.getBoundingBox()), strength + " in free space");
                grenade.discard();
            }
        } finally {
            projectiles(helper).forEach(Entity::discard);
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_grenade_world_border")
    public void launchAndPreviewStopAtWorldBorder(GameTestHelper helper) {
        floor(helper);
        WorldBorder border = helper.getLevel().getWorldBorder();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        double size = border.getSize();
        ServerPlayer player = player(helper, "gs_border", GameType.SURVIVAL, new Vec3(1.5, 1.0, 3.5));
        try {
            double edge = player.getX() + 0.5;
            border.setCenter(edge - 50.0, player.getZ());
            border.setSize(100.0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GrenadeRegistry.item(GrenadeKind.DECOY), 2));
            GrenadeProjectile grenade = GrenadeThrow.handle(player, request(GrenadeThrow.Strength.OVERHAND, 1));
            helper.assertTrue(grenade != null, "throw accepted");
            helper.assertTrue(grenade.getBoundingBox().maxX <= edge + EPS,
                    "spawned inside the border: maxX " + grenade.getBoundingBox().maxX + " edge " + edge);
            GrenadeArc.Path path = GrenadeArc.simulate(GrenadeArc.collider(helper.getLevel()), GrenadeKind.DECOY,
                    grenade.position(), grenade.getDeltaMovement());
            double half = GrenadeTuning.PROJECTILE_SIZE / 2.0;
            for (int i = 0; i < path.size(); i++) {
                helper.assertTrue(path.x(i) + half <= edge + EPS, "preview point " + i + " crosses the border: " + path.point(i));
            }
            grenade.discard();
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
            projectiles(helper).forEach(Entity::discard);
            remove(player);
        }
        helper.succeed();
    }

    /** Ticks a real grenade beside the preview and asserts identical positions every tick. */
    private static GrenadeArc.Path parity(GameTestHelper helper, GrenadeKind kind, Vec3 relative, Vec3 velocity) {
        GrenadeArc.Path path = GrenadeArc.simulate(GrenadeArc.collider(helper.getLevel()), kind,
                helper.absoluteVec(relative), velocity);
        GrenadeProjectile grenade = grenade(helper, kind, relative, velocity, null);
        try {
            for (int i = 1; i < path.size(); i++) {
                helper.assertTrue(!grenade.isRemoved() || i == path.size() - 1 && path.extinguished(),
                        "entity alive at tick " + i);
                Vec3 before = grenade.position();
                grenade.tick();
                assertVec(helper, grenade.position(), path.point(i), kind + " tick " + i + " from " + before);
            }
            if (path.extinguished()) {
                helper.assertTrue(grenade.isRemoved() && grenade.isDetonated(), "server put the fire out where predicted");
            } else if (path.landed() && kind != GrenadeKind.MOLOTOV && kind != GrenadeKind.INCENDIARY) {
                helper.assertTrue(grenade.isSettled(), "server grenade at rest where predicted");
            }
        } finally {
            grenade.discard();
        }
        return path;
    }

    private static dev.csmc.network.GrenadeThrowPayload request(GrenadeThrow.Strength strength, int sequence) {
        return new dev.csmc.network.GrenadeThrowPayload(strength, sequence);
    }

    private static void assertVec(GameTestHelper helper, Vec3 actual, Vec3 expected, String what) {
        helper.assertTrue(actual.distanceTo(expected) < 1.0E-6, what + ": expected " + expected + ", got " + actual);
    }

    // ---------------------------------------------------------------- Physics, fuse, persistence

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void heBouncesFusesOnceAndPersists(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        for (int z = 0; z < 8; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
            }
        }
        double wallFace = helper.absoluteVec(new Vec3(6.0, 0.0, 0.0)).x;
        ServerPlayer owner = player(helper, "gs_fuse", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        Husk husk = husk(helper, new Vec3(3.5, 1.0, 6.5));
        float startHealth = husk.getHealth();
        GrenadeProjectile original = grenade(helper, GrenadeKind.HE, new Vec3(1.5, 1.5, 3.5), new Vec3(0.6, 0.1, 0.0), owner);
        GrenadeProjectile[] current = {original};
        double[] maxX = {Double.NEGATIVE_INFINITY};
        boolean[] bounced = {false};
        float[] afterBlast = {0.0F};
        helper.onEachTick(() -> {
            GrenadeProjectile g = current[0];
            if (!g.isRemoved()) {
                maxX[0] = Math.max(maxX[0], g.getBoundingBox().maxX);
                bounced[0] |= g.getDeltaMovement().x < 0.0;
            }
        });
        helper.runAtTickTime(10, () -> {
            helper.assertTrue(!original.isRemoved() && !original.isDetonated(), "fuse still running at tick 10");
            int flight = original.getFlightTicks();
            helper.assertTrue(flight >= 7 && flight <= 12, "flight ticks near 10, got " + flight);
            CompoundTag tag = new CompoundTag();
            original.saveWithoutId(tag);
            original.discard();
            GrenadeProjectile copy = GrenadeRegistry.PROJECTILE.create(helper.getLevel());
            helper.assertTrue(copy != null, "projectile type creates");
            copy.load(tag);
            copy.setUUID(UUID.randomUUID());
            helper.assertValueEqual(copy.getKind(), GrenadeKind.HE, "saved kind");
            helper.assertValueEqual(copy.getFlightTicks(), flight, "saved remaining fuse");
            helper.assertValueEqual(copy.getOwnerId(), owner.getUUID(), "saved owner");
            helper.getLevel().addFreshEntity(copy);
            current[0] = copy;
        });
        helper.runAtTickTime(26, () -> {
            helper.assertTrue(!current[0].isRemoved() && !current[0].isDetonated(), "no early detonation after reload");
            helper.assertValueEqual(husk.getHealth(), startHealth, "no damage before the fuse");
        });
        helper.runAtTickTime(34, () -> {
            helper.assertTrue(current[0].isDetonated() && current[0].isRemoved(), "reloaded grenade detonated on its fuse");
            helper.assertTrue(!original.isDetonated(), "discarded original never detonated");
            helper.assertTrue(husk.getHealth() < startHealth, "blast hurt the husk");
            afterBlast[0] = husk.getHealth();
        });
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(bounced[0], "grenade bounced off the wall");
            helper.assertTrue(maxX[0] <= wallFace + EPS, "never entered the wall: maxX " + maxX[0] + " face " + wallFace);
            helper.assertValueEqual(husk.getHealth(), afterBlast[0], "exactly one detonation");
            helper.assertValueEqual(projectiles(helper).size(), 0, "no grenade left");
            husk.discard();
            remove(owner);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- HE

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void heFalloffExposureAndRadius(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        ServerPlayer owner = player(helper, "gs_fall", GameType.SURVIVAL, new Vec3(7.5, 6.0, 0.5));
        Husk near = husk(helper, new Vec3(3.5, 1.0, 3.5));
        Husk far = husk(helper, new Vec3(6.5, 1.0, 3.5));
        Husk outside = husk(helper, new Vec3(7.5, 1.0, 7.5));
        float nearStart = near.getHealth();
        float farStart = far.getHealth();
        float outsideStart = outside.getHealth();
        GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(1.5, 1.0, 3.5), Vec3.ZERO, owner);
        Vec3 center = grenade.position().add(0.0, grenade.getBbHeight() * 0.5, 0.0);

        List<GrenadeEffects.BlastHit> hits = GrenadeEffects.explodeHe(helper.getLevel(), grenade, owner, center);
        grenade.discard();

        GrenadeEffects.BlastHit nearHit = hit(helper, hits, near);
        GrenadeEffects.BlastHit farHit = hit(helper, hits, far);
        helper.assertTrue(hits.stream().noneMatch(h -> h.entity() == outside), "beyond the radius is untouched");
        helper.assertValueEqual(outside.getHealth(), outsideStart, "outside health");
        for (GrenadeEffects.BlastHit h : List.of(nearHit, farHit)) {
            double distance = GrenadeEffects.distanceToBox(center, h.entity().getBoundingBox());
            helper.assertTrue(Math.abs(h.csDamage() - GrenadeEffects.heDamageAt(distance)) < EPS, "linear falloff value");
            helper.assertTrue(h.exposure() > 0.5, "open line exposure " + h.exposure());
            double expectedHealth = h.csDamage() * h.exposure() * dev.csmc.ballistics.Ballistics.CS_TO_MC_HEALTH;
            helper.assertTrue(h.healthLost() > 0.85 * expectedHealth && h.healthLost() <= expectedHealth + EPS,
                    "health lost " + h.healthLost() + " vs scaled " + expectedHealth);
        }
        helper.assertTrue(nearHit.csDamage() > farHit.csDamage(), "nearer takes more CS damage");
        helper.assertTrue(nearStart - near.getHealth() > farStart - far.getHealth(), "nearer loses more health");
        helper.assertTrue(near.getDeltaMovement().x > 0.0, "knocked away from the burst");
        helper.assertTrue(GrenadeEffects.heDamageAt(GrenadeTuning.HE_RADIUS) == 0.0, "zero at the radius");
        near.discard();
        far.discard();
        outside.discard();
        remove(owner);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void heCoverShieldsEntitiesAndTerrain(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        for (int z = 0; z < 8; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
            }
        }
        BlockPos shieldedGlass = new BlockPos(4, 1, 2);
        BlockPos exposedGlass = new BlockPos(2, 1, 2);
        helper.setBlock(shieldedGlass, Blocks.GLASS);
        helper.setBlock(exposedGlass, Blocks.GLASS);
        ServerPlayer owner = player(helper, "gs_cover", GameType.SURVIVAL, new Vec3(0.5, 6.0, 7.5));
        Husk covered = husk(helper, new Vec3(5.5, 1.0, 3.5));
        float start = covered.getHealth();
        GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(1.5, 1.0, 3.5), Vec3.ZERO, owner);

        grenade.detonate(helper.getLevel());

        helper.assertTrue(grenade.isRemoved(), "grenade removed");
        helper.assertValueEqual(covered.getHealth(), start, "solid cover blocks all damage");
        if (resistsFullBlast(Blocks.STONE)) {
            for (int z = 0; z < 8; z++) {
                for (int y = 1; y <= 4; y++) {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, y, z));
                }
            }
        }
        helper.assertBlockPresent(Blocks.GLASS, shieldedGlass);
        helper.assertBlockPresent(Blocks.AIR, exposedGlass);
        covered.discard();
        remove(owner);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void heTerrainOneContributionPerCellWithPreBlastOcclusion(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        BlockPos front = new BlockPos(3, 1, 3);
        BlockPos behind = new BlockPos(4, 1, 3);
        BlockPos obsidian = new BlockPos(1, 1, 5);
        BlockPos distant = new BlockPos(7, 1, 7);
        helper.setBlock(front, Blocks.GLASS);
        helper.setBlock(behind, Blocks.GLASS);
        helper.setBlock(obsidian, Blocks.OBSIDIAN);
        helper.setBlock(distant, Blocks.GLASS);
        ServerPlayer owner = player(helper, "gs_cells", GameType.SURVIVAL, new Vec3(7.5, 6.0, 0.5));
        try {
            Vec3 center = helper.absoluteVec(new Vec3(1.5, 1.125, 3.5));
            List<BlockPos> exposed = GrenadeEffects.blastBlocks(helper.getLevel(), owner, center);
            helper.assertValueEqual(exposed.stream().distinct().count(), (long) exposed.size(), "one contribution per cell");
            for (BlockPos pos : exposed) {
                helper.assertTrue(Vec3.atCenterOf(pos).distanceTo(center) <= GrenadeTuning.HE_RADIUS + EPS,
                        "energy bounded by the radius: " + pos);
            }
            helper.assertTrue(exposed.contains(helper.absolutePos(front)), "front glass exposed");
            helper.assertTrue(!exposed.contains(helper.absolutePos(behind)), "glass behind cover occluded by the pre-blast world");
            helper.assertBlockPresent(Blocks.AIR, front);
            helper.assertBlockPresent(Blocks.GLASS, behind);
            helper.assertBlockPresent(Blocks.OBSIDIAN, obsidian);
            helper.assertBlockPresent(Blocks.GLASS, distant);
            if (resistsFullBlast(Blocks.STONE)) {
                for (int x = 0; x < 8; x++) {
                    for (int z = 0; z < 8; z++) {
                        helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
                    }
                }
            }
        } finally {
            remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void ownerLogoutFailsClosed(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        BlockPos glass = new BlockPos(2, 1, 2);
        helper.setBlock(glass, Blocks.GLASS);
        ServerPlayer owner = player(helper, "gs_gone", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        Husk blastTarget = husk(helper, new Vec3(3.5, 1.0, 3.5));
        Husk fireTarget = husk(helper, new Vec3(5.5, 1.0, 6.5));
        float blastStart = blastTarget.getHealth();
        float fireStart = fireTarget.getHealth();
        GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(1.5, 1.0, 3.5), Vec3.ZERO, owner);
        GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                helper.absoluteVec(new Vec3(5.5, 1.0, 6.5)), owner.getUUID());
        helper.assertTrue(fire != null, "fire pool spawned");
        remove(owner);
        helper.assertTrue(GrenadeEffects.resolveOwner(helper.getLevel(), owner.getUUID()) == null, "owner unresolved");

        grenade.detonate(helper.getLevel());

        helper.assertTrue(grenade.isDetonated(), "detonated");
        helper.assertBlockPresent(Blocks.GLASS, glass);
        helper.assertValueEqual(blastTarget.getHealth(), blastStart, "ownerless blast harms nothing");
        helper.runAtTickTime(30, () -> {
            helper.assertValueEqual(fireTarget.getHealth(), fireStart, "ownerless fire burns nothing");
            helper.assertBlockPresent(Blocks.GLASS, glass);
            fire.discard();
            blastTarget.discard();
            fireTarget.discard();
            helper.succeed();
        });
    }

    /** Own batch: batches run sequentially, so flipping the global gamerule cannot race other tests. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_grenade_no_block_breaking", timeoutTicks = 100)
    public void blockBreakingGameruleProtectsTerrain(GameTestHelper helper) {
        floor(helper);
        boolean priorRule = helper.getLevel().getGameRules().getBoolean(BlockBreakPolicy.BLOCK_BREAKING_RULE);
        setBlockBreaking(helper, false);
        BlockPos glass = new BlockPos(2, 1, 2);
        BlockPos plank = new BlockPos(5, 0, 5);
        helper.setBlock(glass, Blocks.GLASS);
        helper.setBlock(plank, Blocks.OAK_PLANKS);
        ServerPlayer owner = player(helper, "gs_rule", GameType.SURVIVAL, new Vec3(0.5, 6.0, 7.5));
        try {
            GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(1.5, 1.0, 3.5), Vec3.ZERO, owner);
            grenade.detonate(helper.getLevel());
            helper.assertBlockPresent(Blocks.GLASS, glass);
            GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.INCENDIARY,
                    helper.absoluteVec(new Vec3(5.5, 1.0, 5.5)), owner.getUUID());
            helper.assertTrue(fire != null && fire.isBurning(helper.absolutePos(plank.above())), "pool over the plank");
        } catch (RuntimeException e) {
            setBlockBreaking(helper, priorRule);
            remove(owner);
            throw e;
        }
        helper.runAtTickTime(70, () -> {
            try {
                helper.assertBlockPresent(Blocks.OAK_PLANKS, plank);
                helper.assertBlockPresent(Blocks.GLASS, glass);
                helper.succeed();
            } finally {
                setBlockBreaking(helper, priorRule);
                clouds(helper).forEach(Entity::discard);
                remove(owner);
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_grenade_pvp_off")
    public void pvpOffSparesPlayers(GameTestHelper helper) {
        floor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        boolean pvp = server.isPvpAllowed();
        ServerPlayer owner = player(helper, "gs_pvpa", GameType.SURVIVAL, new Vec3(1.5, 1.0, 0.5));
        ServerPlayer target = player(helper, "gs_pvpb", GameType.SURVIVAL, new Vec3(3.5, 1.0, 3.5));
        Husk husk = husk(helper, new Vec3(3.5, 1.0, 5.5));
        try {
            server.setPvpAllowed(false);
            helper.assertTrue(!GrenadeEffects.mayHarm(owner, target), "pvp off guards players");
            helper.assertTrue(GrenadeEffects.mayHarm(owner, owner), "self damage stays allowed");
            GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(2.5, 1.0, 4.5), Vec3.ZERO, owner);
            List<GrenadeEffects.BlastHit> hits = GrenadeEffects.explodeHe(helper.getLevel(), grenade, owner,
                    grenade.position().add(0.0, 0.125, 0.0));
            grenade.discard();
            helper.assertTrue(hits.stream().noneMatch(h -> h.entity() == target), "HE skips the protected player");
            hit(helper, hits, husk);
            GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                    helper.absoluteVec(new Vec3(3.5, 1.0, 4.5)), owner.getUUID());
            helper.assertTrue(fire != null && fire.standsInFire(target) && fire.standsInFire(husk), "both stand in the pool");
            List<Entity> burn = fire.burnTargets(owner);
            helper.assertTrue(!burn.contains(target) && burn.contains(husk), "fire skips the protected player: " + burn);
            fire.discard();
        } finally {
            server.setPvpAllowed(pvp);
            husk.discard();
            remove(owner);
            remove(target);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_grenade_pvp_teams")
    public void teamFriendlyFireGuard(GameTestHelper helper) {
        floor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        boolean pvp = server.isPvpAllowed();
        ServerPlayer owner = player(helper, "gs_teama", GameType.SURVIVAL, new Vec3(1.5, 1.0, 0.5));
        ServerPlayer mate = player(helper, "gs_teamb", GameType.SURVIVAL, new Vec3(3.5, 1.0, 3.5));
        ServerPlayer enemy = player(helper, "gs_teamc", GameType.SURVIVAL, new Vec3(3.5, 1.0, 5.5));
        PlayerTeam team = server.getScoreboard().addPlayerTeam("gs_grenade_team");
        try {
            server.setPvpAllowed(true);
            team.setAllowFriendlyFire(false);
            server.getScoreboard().addPlayerToTeam(owner.getScoreboardName(), team);
            server.getScoreboard().addPlayerToTeam(mate.getScoreboardName(), team);
            helper.assertTrue(!GrenadeEffects.mayHarm(owner, mate) && GrenadeEffects.mayHarm(owner, enemy), "team guard");
            GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(2.5, 1.0, 4.5), Vec3.ZERO, owner);
            List<GrenadeEffects.BlastHit> hits = GrenadeEffects.explodeHe(helper.getLevel(), grenade, owner,
                    grenade.position().add(0.0, 0.125, 0.0));
            grenade.discard();
            helper.assertTrue(hits.stream().noneMatch(h -> h.entity() == mate), "HE skips the teammate");
            hit(helper, hits, enemy);
            GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.INCENDIARY,
                    helper.absoluteVec(new Vec3(3.5, 1.0, 4.5)), owner.getUUID());
            helper.assertTrue(fire != null, "fire pool spawned");
            List<Entity> burn = fire.burnTargets(owner);
            helper.assertTrue(!burn.contains(mate) && burn.contains(enemy), "fire skips the teammate: " + burn);
            fire.discard();
        } finally {
            server.getScoreboard().removePlayerTeam(team);
            server.setPvpAllowed(pvp);
            remove(owner);
            remove(mate);
            remove(enemy);
        }
        helper.succeed();
    }

    /** Vanilla rejects player-attributed self damage under PvP off; the explicit self allowance must still hurt. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_grenade_self_pvp_off", timeoutTicks = 60)
    public void selfDamageWithPvpOff(GameTestHelper helper) {
        selfDamage(helper, "gs_selfp", false);
    }

    /** Vanilla rejects player-attributed self damage when the own team disallows friendly fire. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_grenade_self_team", timeoutTicks = 60)
    public void selfDamageWithTeamFriendlyFireOff(GameTestHelper helper) {
        selfDamage(helper, "gs_selft", true);
    }

    /**
     * Owner and a protected second player (PvP off, or same team without friendly fire) after spawn immunity:
     * HE and the owner's fire pool really remove the owner's health, never the protected player's.
     */
    private static void selfDamage(GameTestHelper helper, String name, boolean team) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        boolean pvp = server.isPvpAllowed();
        Difficulty difficulty = server.getWorldData().getDifficulty();
        ServerPlayer owner = player(helper, name, GameType.SURVIVAL, new Vec3(5.5, 1.0, 3.5));
        ServerPlayer other = player(helper, name + "o", GameType.SURVIVAL, new Vec3(5.5, 1.0, 5.5));
        PlayerTeam playerTeam = team ? server.getScoreboard().addPlayerTeam(name + "_team") : null;
        Runnable restore = () -> {
            if (playerTeam != null) {
                server.getScoreboard().removePlayerTeam(playerTeam);
            }
            server.setPvpAllowed(pvp);
            if (server.getWorldData().getDifficulty() != difficulty) {
                server.setDifficulty(difficulty, true);
            }
            clouds(helper).forEach(Entity::discard);
            remove(owner);
            remove(other);
        };
        float[] afterBlast = {0.0F};
        float[] otherStart = {0.0F};
        try {
            // Explosion damage scales with difficulty and is zero on peaceful.
            if (difficulty == Difficulty.PEACEFUL) {
                server.setDifficulty(Difficulty.NORMAL, true);
            }
            if (playerTeam != null) {
                server.setPvpAllowed(true);
                playerTeam.setAllowFriendlyFire(false);
                server.getScoreboard().addPlayerToTeam(owner.getScoreboardName(), playerTeam);
                server.getScoreboard().addPlayerToTeam(other.getScoreboardName(), playerTeam);
            } else {
                server.setPvpAllowed(false);
            }
            tickPlayer(owner, 61);
            tickPlayer(other, 61);
            float ownerStart = owner.getHealth();
            otherStart[0] = other.getHealth();
            GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(2.5, 1.0, 4.5), Vec3.ZERO, owner);
            grenade.detonate(level);
            helper.assertTrue(owner.getHealth() < ownerStart, "HE must hurt its own thrower: " + owner.getHealth());
            helper.assertValueEqual(other.getHealth(), otherStart[0], "protected player untouched by HE");
            tickPlayer(owner, 21); // clear the vanilla hurt cooldown before the fire pulse
            afterBlast[0] = owner.getHealth();
            GrenadeCloud fire = GrenadeCloud.spawnFire(level, GrenadeKind.MOLOTOV,
                    helper.absoluteVec(new Vec3(5.5, 1.0, 4.5)), owner.getUUID());
            helper.assertTrue(fire != null && fire.standsInFire(owner) && fire.standsInFire(other), "both stand in the pool");
        } catch (RuntimeException e) {
            restore.run();
            throw e;
        }
        helper.runAtTickTime(15, () -> {
            try {
                helper.assertTrue(owner.getHealth() < afterBlast[0], "own fire must burn its thrower: " + owner.getHealth());
                helper.assertValueEqual(other.getHealth(), otherStart[0], "protected player untouched by fire");
                helper.succeed();
            } finally {
                restore.run();
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void heBreaksBottomSlabItRestsOn(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        BlockPos slab = new BlockPos(3, 1, 3);
        helper.setBlock(slab, Blocks.OAK_SLAB);
        ServerPlayer owner = player(helper, "gs_slab", GameType.SURVIVAL, new Vec3(7.5, 6.0, 0.5));
        try {
            GrenadeProjectile grenade = grenade(helper, GrenadeKind.HE, new Vec3(3.5, 1.5, 3.5), Vec3.ZERO, owner);
            Vec3 center = grenade.position().add(0.0, grenade.getBbHeight() * 0.5, 0.0);
            helper.assertValueEqual(BlockPos.containing(center), helper.absolutePos(slab), "burst inside the slab's cell");
            MaterialTable.Material material = MaterialTable.lookup(Blocks.OAK_SLAB.defaultBlockState());
            double energy = GrenadeEffects.heDamageAt(Vec3.atCenterOf(helper.absolutePos(slab)).distanceTo(center));
            helper.assertTrue(material.breakMode() == MaterialTable.BreakMode.SHATTER
                    || material.breakMode() == MaterialTable.BreakMode.CUMULATIVE && material.breakDamage() <= energy,
                    "fixture needs a slab the point-blank blast can break: " + material + " vs " + energy);
            grenade.detonate(helper.getLevel());
            helper.assertBlockPresent(Blocks.AIR, slab);
        } finally {
            remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void fireAirburstUnderTopSlabStaysBelowCeiling(GameTestHelper helper) {
        floor(helper);
        BlockPos ceiling = new BlockPos(3, 2, 3);
        helper.setBlock(ceiling, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        ServerPlayer owner = player(helper, "gs_ceil", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        Husk upstairs = husk(helper, new Vec3(3.5, 3.0, 3.5));
        float start = upstairs.getHealth();
        // Fuse burst in the empty lower half of the top slab.
        GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                helper.absoluteVec(new Vec3(3.5, 2.2, 3.5)), owner.getUUID());
        try {
            helper.assertTrue(fire != null, "burst under the ceiling lands on the floor below");
            helper.assertTrue(fire.isBurning(helper.absolutePos(new BlockPos(3, 1, 3))), "floor cell below the burst burns");
            int ceilingY = helper.absolutePos(ceiling).getY();
            for (long packed : fire.getFireCells().toLongArray()) {
                helper.assertTrue(BlockPos.of(packed).getY() < ceilingY, "never above the ceiling: " + BlockPos.of(packed));
            }
            helper.assertTrue(!fire.standsInFire(upstairs), "upstairs occupant not in the pool");
        } catch (RuntimeException e) {
            clouds(helper).forEach(Entity::discard);
            upstairs.discard();
            remove(owner);
            throw e;
        }
        helper.runAtTickTime(25, () -> {
            try {
                helper.assertValueEqual(upstairs.getHealth(), start, "ceiling protects upstairs");
                helper.succeed();
            } finally {
                clouds(helper).forEach(Entity::discard);
                upstairs.discard();
                remove(owner);
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void bottomSlabFloorBurnsOccupants(GameTestHelper helper) {
        floor(helper);
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE_SLAB);
            }
        }
        ServerPlayer owner = player(helper, "gs_bslab", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        Husk onSlab = husk(helper, new Vec3(3.5, 1.5, 3.5));
        float start = onSlab.getHealth();
        GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                helper.absoluteVec(new Vec3(3.5, 1.5, 4.5)), owner.getUUID());
        try {
            helper.assertTrue(fire != null, "pool on the slab floor");
            helper.assertTrue(fire.standsInFire(onSlab), "occupant on the slab surface touches the fire");
        } catch (RuntimeException e) {
            clouds(helper).forEach(Entity::discard);
            onSlab.discard();
            remove(owner);
            throw e;
        }
        helper.runAtTickTime(25, () -> {
            try {
                helper.assertTrue(onSlab.getHealth() < start, "slab occupant burns");
                helper.succeed();
            } finally {
                clouds(helper).forEach(Entity::discard);
                onSlab.discard();
                remove(owner);
            }
        });
    }

    /**
     * A burst on a bottom stair's lower tread (local surface half a block below the stair's top) lands on the tread,
     * never in the room under the stair: fire stays above the stone ceiling, the tread occupant burns and the room
     * occupant does not.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void fireOnStairTreadStaysAboveRoomBelow(GameTestHelper helper) {
        floor(helper);
        int ceilingY = 3;
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, ceilingY, z), Blocks.STONE);
            }
        }
        // Bottom stair facing north: full step on the north half (z 0..0.5), lower tread on the south half.
        BlockPos stair = new BlockPos(3, ceilingY, 3);
        helper.setBlock(stair, Blocks.STONE_STAIRS.defaultBlockState());
        // Bottom slab south of the stair continues the tread height so the occupant fits.
        helper.setBlock(stair.south(), Blocks.STONE_SLAB);
        ServerPlayer owner = player(helper, "gs_stair", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        Husk room = husk(helper, new Vec3(3.5, 1.0, 3.5));
        Husk tread = husk(helper, new Vec3(3.5, ceilingY + 0.5, 3.85));
        float roomStart = room.getHealth();
        float treadStart = tread.getHealth();
        GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                helper.absoluteVec(new Vec3(3.5, ceilingY + 0.5, 3.75)), owner.getUUID());
        try {
            helper.assertTrue(fire != null, "pool on the stair tread");
            int ceilingAbs = helper.absolutePos(stair).getY();
            for (long packed : fire.getFireCells().toLongArray()) {
                helper.assertTrue(BlockPos.of(packed).getY() > ceilingAbs, "never below the stair/ceiling: " + BlockPos.of(packed));
            }
            helper.assertTrue(!fire.standsInFire(room), "room occupant not in the pool");
            helper.assertTrue(fire.standsInFire(tread), "tread occupant touches the fire at the tread's local height");
        } catch (RuntimeException e) {
            clouds(helper).forEach(Entity::discard);
            room.discard();
            tread.discard();
            remove(owner);
            throw e;
        }
        helper.runAtTickTime(25, () -> {
            try {
                helper.assertValueEqual(room.getHealth(), roomStart, "stair protects the room below");
                helper.assertTrue(tread.getHealth() < treadStart, "tread occupant burns");
                helper.succeed();
            } finally {
                clouds(helper).forEach(Entity::discard);
                room.discard();
                tread.discard();
                remove(owner);
            }
        });
    }

    /**
     * Fire contact never reaches past an actual surface: a pool on a full-block platform does not touch an
     * occupant on the ground whose box overlaps the platform column (the cell one above its feet), nor an
     * occupant standing on a full block one floor above the pool.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void fireContactStopsAtFullBlockSurfaces(GameTestHelper helper) {
        floor(helper);
        for (int x = 4; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        // Water trench beside the platform so the ground there can never hold fire cells.
        for (int z = 1; z <= 5; z++) {
            helper.setBlock(new BlockPos(2, 1, z), Blocks.STONE);
        }
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.STONE);
        for (int z = 2; z <= 4; z++) {
            helper.setBlock(new BlockPos(3, 1, z), Blocks.WATER);
        }
        BlockPos upperFloor = new BlockPos(6, 3, 4);
        helper.setBlock(upperFloor, Blocks.STONE);
        ServerPlayer owner = player(helper, "gs_edge", GameType.SURVIVAL, new Vec3(0.5, 6.0, 7.5));
        ServerPlayer onPlatform = player(helper, "gs_edgep", GameType.SURVIVAL, new Vec3(5.5, 2.0, 3.5));
        ServerPlayer beside = player(helper, "gs_edgeb", GameType.SURVIVAL, new Vec3(3.9, 1.0, 3.5));
        ServerPlayer upstairs = player(helper, "gs_edgeu", GameType.SURVIVAL, new Vec3(6.5, 4.0, 4.5));
        try {
            GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                    helper.absoluteVec(new Vec3(5.5, 2.0, 3.5)), owner.getUUID());
            helper.assertTrue(fire != null, "pool on the platform");
            helper.assertTrue(fire.isBurning(helper.absolutePos(new BlockPos(4, 2, 3))), "platform edge burns");
            helper.assertTrue(fire.isBurning(helper.absolutePos(upperFloor.below())), "cell under the upper floor burns");
            helper.assertTrue(!fire.isBurning(helper.absolutePos(new BlockPos(3, 1, 3))), "water trench never burns");
            helper.assertTrue(fire.standsInFire(onPlatform), "control: occupant on the platform touches the fire");
            helper.assertTrue(!fire.standsInFire(beside), "ground occupant below the platform surface is not in the fire");
            helper.assertTrue(!fire.standsInFire(upstairs), "occupant on the full block one floor up is not in the fire");
        } finally {
            clouds(helper).forEach(Entity::discard);
            remove(owner);
            remove(onPlatform);
            remove(beside);
            remove(upstairs);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- Flashbang

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void flashUsesLineOfSightFacingAndRange(GameTestHelper helper) {
        floor(helper);
        for (int x = 0; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
            }
        }
        BlockPos glass = new BlockPos(2, 1, 2);
        helper.setBlock(glass, Blocks.GLASS);
        ServerLevel level = helper.getLevel();
        ServerPlayer facing = player(helper, "gs_flsa", GameType.SURVIVAL, new Vec3(4.5, 1.0, 3.5));
        look(facing, 90.0F);
        ServerPlayer hidden = player(helper, "gs_flsb", GameType.SURVIVAL, new Vec3(1.5, 1.0, 6.5));
        look(hidden, 180.0F);
        Husk husk = husk(helper, new Vec3(2.5, 1.0, 3.5));
        float huskStart = husk.getHealth();
        Vec3 center = helper.absoluteVec(new Vec3(1.5, 1.5, 3.5));
        try {
            Vec3 eye = facing.getEyePosition();
            Vec3 toward = center.subtract(eye).normalize();
            double front = GrenadeEffects.flashSeverity(level, center, eye, toward, null);
            double away = GrenadeEffects.flashSeverity(level, center, eye, toward.scale(-1.0), null);
            helper.assertTrue(front > 0.8, "facing a near flash is severe: " + front);
            helper.assertTrue(away > 0.0 && away < front * 0.5, "facing away is weaker: " + away + " vs " + front);
            Vec3 farEye = center.add(GrenadeTuning.FLASH_RADIUS + 5.0, 0.0, 0.0);
            helper.assertValueEqual(GrenadeEffects.flashSeverity(level, center, farEye,
                    center.subtract(farEye).normalize(), null), 0.0, "beyond range");
            double nearer = GrenadeEffects.flashSeverity(level, center, center.add(0.0, 4.0, 0.0), new Vec3(0, -1, 0), null);
            double farther = GrenadeEffects.flashSeverity(level, center, center.add(0.0, 6.0, 0.0), new Vec3(0, -1, 0), null);
            helper.assertTrue(nearer > farther && farther > 0.0, "distance falloff " + nearer + " > " + farther);

            List<GrenadeEffects.FlashHit> hits = GrenadeEffects.flash(level, center);
            GrenadeEffects.FlashHit facingHit = hits.stream().filter(h -> h.player() == facing).findFirst().orElse(null);
            helper.assertTrue(facingHit != null, "line-of-sight player flashed");
            helper.assertTrue(Math.abs(facingHit.severity() - GrenadeEffects.flashSeverity(level, center,
                    facing.getEyePosition(), facing.getViewVector(1.0F), facing)) < EPS, "server severity from view");
            helper.assertTrue(facingHit.payload().holdTicks() <= GrenadeTuning.FLASH_MAX_HOLD_TICKS
                    && facingHit.payload().fadeTicks() <= GrenadeTuning.FLASH_MAX_FADE_TICKS
                    && facingHit.payload().holdTicks() > 0, "bounded whiteout " + facingHit.payload());
            helper.assertTrue(hits.stream().noneMatch(h -> h.player() == hidden), "wall blocks the flash");
            helper.assertValueEqual(husk.getHealth(), huskStart, "flash deals no damage");
            helper.assertBlockPresent(Blocks.GLASS, glass);
        } finally {
            husk.discard();
            remove(facing);
            remove(hidden);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- Smoke

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void smokeSettlesGrowsAndExpires(GameTestHelper helper) {
        floor(helper);
        GrenadeProjectile smokeGrenade = grenade(helper, GrenadeKind.SMOKE, new Vec3(2.5, 1.0, 3.5), new Vec3(0.2, 0.0, 0.0), null);
        GrenadeCloud[] cloud = {null};
        int[] spawnedAt = {-1};
        helper.onEachTick(() -> {
            if (cloud[0] == null) {
                List<GrenadeCloud> clouds = clouds(helper);
                if (!clouds.isEmpty()) {
                    cloud[0] = clouds.get(0);
                    spawnedAt[0] = (int) helper.getTick();
                }
            }
        });
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(cloud[0] != null && smokeGrenade.isRemoved(), "smoke activated once at rest");
            helper.assertValueEqual(cloud[0].getKind(), GrenadeKind.SMOKE, "smoke kind");
            helper.assertValueEqual(clouds(helper).size(), 1, "one smoke cloud");
            helper.assertValueEqual(cloud[0].getDurationTicks(), GrenadeTuning.SMOKE_DURATION_TICKS, "duration");
            helper.assertTrue(spawnedAt[0] <= 40 - GrenadeTuning.SMOKE_GROW_TICKS - 1
                    || cloud[0].getRadius() < GrenadeTuning.SMOKE_RADIUS, "growth not instant");
        });
        helper.runAtTickTime(45, () -> {
            GrenadeCloud smoke = cloud[0];
            int age = smoke.getAgeTicks();
            float expected = GrenadeTuning.SMOKE_RADIUS * Math.min(1.0F, (float) age / GrenadeTuning.SMOKE_GROW_TICKS);
            helper.assertTrue(Math.abs(smoke.getRadius() - expected) < 1.0E-4, "radius follows age");
            CompoundTag tag = new CompoundTag();
            smoke.saveWithoutId(tag);
            tag.putInt("Age", GrenadeTuning.SMOKE_DURATION_TICKS - 5);
            smoke.discard();
            GrenadeCloud aged = GrenadeRegistry.CLOUD.create(helper.getLevel());
            aged.load(tag);
            aged.setUUID(UUID.randomUUID());
            helper.assertValueEqual(aged.getRadius(), GrenadeTuning.SMOKE_RADIUS, "full radius after load");
            helper.getLevel().addFreshEntity(aged);
            cloud[0] = aged;
        });
        helper.runAtTickTime(55, () -> {
            helper.assertTrue(cloud[0].isRemoved(), "smoke expires at its duration");
            helper.assertValueEqual(clouds(helper).size(), 0, "no smoke left");
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- Fire

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void molotovLandsIgnitesAndWaterFizzles(GameTestHelper helper) {
        floor(helper);
        BlockPos water = new BlockPos(1, 1, 1);
        for (BlockPos wall : List.of(water.west(), water.east(), water.north(), water.south())) {
            helper.setBlock(wall, Blocks.STONE);
        }
        helper.setBlock(water, Blocks.WATER);
        ServerPlayer owner = player(helper, "gs_molo", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        GrenadeProjectile molotov = grenade(helper, GrenadeKind.MOLOTOV, new Vec3(4.5, 3.0, 4.5), new Vec3(0.0, -0.3, 0.0), owner);
        GrenadeProjectile wet = grenade(helper, GrenadeKind.INCENDIARY, new Vec3(1.5, 3.0, 1.5), new Vec3(0.0, -0.3, 0.0), owner);
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(molotov.isDetonated() && molotov.isRemoved(), "molotov detonated on the floor before its fuse");
            helper.assertTrue(wet.isRemoved(), "incendiary in water fizzled");
            List<GrenadeCloud> clouds = clouds(helper);
            helper.assertValueEqual(clouds.size(), 1, "only the dry molotov burns");
            GrenadeCloud fire = clouds.get(0);
            helper.assertValueEqual(fire.getKind(), GrenadeKind.MOLOTOV, "pool kind");
            helper.assertValueEqual(fire.getOwnerId(), owner.getUUID(), "pool owner");
            helper.assertTrue(fire.isBurning(helper.absolutePos(new BlockPos(4, 1, 4))), "landing cell burns");
            helper.assertTrue(GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                    helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)), owner.getUUID()) == null, "a pool cannot start in water");
            fire.discard();
            remove(owner);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void fireIsBoundedByWallsAndFloors(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        for (int z = 0; z < 8; z++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(5, y, z), Blocks.STONE);
            }
        }
        for (int x = 1; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 3, 1), Blocks.STONE);
        }
        ServerPlayer owner = player(helper, "gs_bound", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        Husk inPool = husk(helper, new Vec3(3.5, 1.0, 4.5));
        Husk behindWall = husk(helper, new Vec3(6.5, 1.0, 3.5));
        Husk upstairs = husk(helper, new Vec3(2.5, 4.0, 1.5));
        float poolStart = inPool.getHealth();
        float wallStart = behindWall.getHealth();
        float upStart = upstairs.getHealth();
        GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                helper.absoluteVec(new Vec3(3.5, 1.0, 3.5)), owner.getUUID());
        helper.assertTrue(fire != null, "fire pool spawned");
        BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 3));
        int wallX = helper.absolutePos(new BlockPos(5, 0, 0)).getX();
        var cells = fire.getFireCells();
        helper.assertTrue(!cells.isEmpty() && cells.size() <= GrenadeTuning.FIRE_MAX_CELLS, "bounded cell count " + cells.size());
        for (long packed : cells.toLongArray()) {
            BlockPos cell = BlockPos.of(packed);
            double dx = cell.getX() - origin.getX();
            double dz = cell.getZ() - origin.getZ();
            helper.assertTrue(dx * dx + dz * dz <= GrenadeTuning.FIRE_RADIUS * GrenadeTuning.FIRE_RADIUS + EPS, "within radius " + cell);
            helper.assertTrue(cell.getY() == origin.getY(), "stays on the landing floor " + cell);
            helper.assertTrue(cell.getX() < wallX, "never crosses the wall " + cell);
        }
        helper.assertTrue(!fire.standsInFire(behindWall) && !fire.standsInFire(upstairs), "only the connected floor burns");
        helper.runAtTickTime(25, () -> {
            helper.assertTrue(inPool.getHealth() < poolStart, "entity in the pool burns");
            helper.assertValueEqual(behindWall.getHealth(), wallStart, "sealed wall protects");
            helper.assertValueEqual(upstairs.getHealth(), upStart, "other floor protected");
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(0, 1, 0), new BlockPos(7, 4, 7))) {
                helper.assertTrue(!helper.getBlockState(pos).is(Blocks.FIRE), "no vanilla fire blocks at " + pos);
            }
            fire.discard();
            inPool.discard();
            behindWall.discard();
            upstairs.discard();
            remove(owner);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 160)
    public void fireBurnsFlammableBlocksThroughSharedDamage(GameTestHelper helper) {
        floor(helper);
        setBlockBreaking(helper, true);
        BlockPos plank = new BlockPos(3, 0, 4);
        BlockPos stone = new BlockPos(4, 0, 4);
        helper.setBlock(plank, Blocks.OAK_PLANKS);
        ServerPlayer owner = player(helper, "gs_burn", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        GrenadeCloud fire = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.INCENDIARY,
                helper.absoluteVec(new Vec3(3.5, 1.0, 3.5)), owner.getUUID());
        helper.assertTrue(fire != null && fire.isBurning(helper.absolutePos(plank.above())), "pool covers the plank");
        MaterialTable.Material material = MaterialTable.lookup(Blocks.OAK_PLANKS.defaultBlockState());
        int hits = switch (material.breakMode()) {
            case SHATTER -> 1;
            case CUMULATIVE -> (int) Math.ceil(material.breakDamage() / GrenadeTuning.FIRE_BLOCK_DAMAGE);
            case NONE -> Integer.MAX_VALUE;
        };
        int maxHits = GrenadeTuning.FIRE_DURATION_TICKS / GrenadeTuning.FIRE_BLOCK_INTERVAL_TICKS - 1;
        helper.runAtTickTime(GrenadeTuning.FIRE_DURATION_TICKS - 5, () -> {
            if (hits <= maxHits) {
                helper.assertBlockPresent(Blocks.AIR, plank);
                helper.assertTrue(!fire.isBurning(helper.absolutePos(plank.above())), "cell without floor stops burning");
            } else {
                helper.assertBlockPresent(Blocks.OAK_PLANKS, plank);
            }
            helper.assertBlockPresent(Blocks.STONE, stone);
            clouds(helper).forEach(Entity::discard);
            remove(owner);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void waterAndSmokeExtinguishFire(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = player(helper, "gs_wet", GameType.SURVIVAL, new Vec3(7.5, 6.0, 7.5));
        for (int i = 0; i <= 3; i++) {
            helper.setBlock(new BlockPos(3, 1, i), Blocks.STONE);
            helper.setBlock(new BlockPos(i, 1, 3), Blocks.STONE);
        }
        GrenadeCloud wetPool = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.MOLOTOV,
                helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)), owner.getUUID());
        GrenadeCloud smokedPool = GrenadeCloud.spawnFire(helper.getLevel(), GrenadeKind.INCENDIARY,
                helper.absoluteVec(new Vec3(5.5, 1.0, 5.5)), owner.getUUID());
        helper.assertTrue(wetPool != null && smokedPool != null, "pools spawned");
        BlockPos wetCell = new BlockPos(1, 1, 1);
        helper.assertTrue(wetPool.isBurning(helper.absolutePos(wetCell)), "cell burns before water");
        helper.setBlock(wetCell, Blocks.WATER);
        GrenadeCloud smoke = GrenadeCloud.spawnSmoke(helper.getLevel(), helper.absoluteVec(new Vec3(5.5, 1.0, 5.5)));
        helper.runAtTickTime(30, () -> {
            helper.assertTrue(wetPool.isRemoved() || !wetPool.isBurning(helper.absolutePos(wetCell)), "water removes the wet cell");
            helper.assertTrue(smokedPool.isRemoved(), "smoke extinguishes the whole pool inside it");
            helper.assertTrue(!smoke.isRemoved(), "smoke persists");
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(4, 1, 4), new BlockPos(7, 4, 7))) {
                helper.assertTrue(helper.getBlockState(pos).isAir(), "smoke placed no blocks at " + pos);
            }
            clouds(helper).forEach(Entity::discard);
            remove(owner);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- Decoy

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 80)
    public void decoyReportsWithoutDamageOrAmmo(GameTestHelper helper) {
        floor(helper);
        BlockPos glass = new BlockPos(4, 1, 3);
        helper.setBlock(glass, Blocks.GLASS);
        ServerPlayer owner = player(helper, "gs_decoy", GameType.SURVIVAL, new Vec3(0.5, 1.0, 0.5));
        ItemStack gun = new ItemStack(CsmcMod.AK47);
        gun.set(CsmcMod.MAGAZINE, 17);
        owner.getInventory().setItem(0, gun);
        owner.getInventory().setItem(1, new ItemStack(CsmcMod.RIFLE_AMMO, 10));
        Husk husk = husk(helper, new Vec3(3.5, 1.0, 4.5));
        float start = husk.getHealth();
        GrenadeProjectile decoy = grenade(helper, GrenadeKind.DECOY, new Vec3(3.5, 1.0, 3.5), Vec3.ZERO, owner);
        GrenadeProjectile[] finishing = {null};
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(decoy.isSettled() && decoy.isActive() && !decoy.isRemoved(), "decoy active at rest");
            helper.assertTrue(decoy.getReportCount() >= 1, "decoy emitted reports: " + decoy.getReportCount());
            helper.assertValueEqual(husk.getHealth(), start, "reports deal no damage");
            helper.assertValueEqual(owner.getInventory().getItem(0).get(CsmcMod.MAGAZINE), 17, "owner magazine untouched");
            helper.assertValueEqual(owner.getInventory().getItem(1).getCount(), 10, "owner ammo untouched");
            CompoundTag tag = new CompoundTag();
            decoy.saveWithoutId(tag);
            tag.putInt("ActiveTicks", GrenadeTuning.DECOY_DURATION_TICKS - 3);
            decoy.discard();
            GrenadeProjectile copy = GrenadeRegistry.PROJECTILE.create(helper.getLevel());
            copy.load(tag);
            copy.setUUID(UUID.randomUUID());
            helper.assertTrue(copy.isActive() && copy.getReportCount() == decoy.getReportCount(), "decoy state persists");
            helper.getLevel().addFreshEntity(copy);
            finishing[0] = copy;
        });
        helper.runAtTickTime(50, () -> {
            helper.assertTrue(finishing[0].isDetonated() && finishing[0].isRemoved(), "decoy ends after its duration");
            helper.assertValueEqual(husk.getHealth(), start, "final pop deals no damage");
            helper.assertBlockPresent(Blocks.GLASS, glass);
            helper.assertValueEqual(owner.getInventory().getItem(0).get(CsmcMod.MAGAZINE), 17, "magazine still untouched");
            husk.discard();
            remove(owner);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- Bounds

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void savedCloudBoundsAreClamped(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(3.5, 1.0, 3.5));
        BlockPos origin = BlockPos.containing(pos);
        CompoundTag fireTag = new CompoundTag();
        fireTag.putString("Kind", "molotov");
        fireTag.putFloat("Radius", 1000.0F);
        fireTag.putInt("Duration", 1_000_000);
        fireTag.putInt("Age", -50);
        fireTag.putLongArray("Cells", new long[] {origin.asLong(), origin.offset(40, 0, 0).asLong(), origin.offset(0, 9, 0).asLong()});
        GrenadeCloud fire = GrenadeRegistry.CLOUD.create(level);
        fire.moveTo(pos.x, pos.y, pos.z);
        fire.readAdditionalSaveData(fireTag);
        helper.assertValueEqual(fire.getMaxRadius(), GrenadeTuning.FIRE_RADIUS, "fire radius clamped");
        helper.assertValueEqual(fire.getDurationTicks(), GrenadeTuning.FIRE_DURATION_TICKS, "fire duration clamped");
        helper.assertValueEqual(fire.getAgeTicks(), 0, "age non-negative");
        helper.assertValueEqual(fire.getFireCells().size(), 1, "far saved cells dropped");

        CompoundTag smokeTag = new CompoundTag();
        smokeTag.putString("Kind", "smokegrenade");
        smokeTag.putFloat("Radius", 64.0F);
        GrenadeCloud smoke = GrenadeRegistry.CLOUD.create(level);
        smoke.readAdditionalSaveData(smokeTag);
        helper.assertValueEqual(smoke.getMaxRadius(), GrenadeTuning.SMOKE_RADIUS, "smoke radius clamped");

        CompoundTag bogus = new CompoundTag();
        bogus.putString("Kind", "hegrenade");
        GrenadeCloud notACloudKind = GrenadeRegistry.CLOUD.create(level);
        notACloudKind.readAdditionalSaveData(bogus);
        helper.assertValueEqual(notACloudKind.getKind(), GrenadeKind.SMOKE, "HE cannot become a cloud");
        helper.succeed();
    }

    // ---------------------------------------------------------------- Fixtures

    /** Hard cover per the shipped material table: never breaks, or needs more than a point-blank HE contribution. */
    private static boolean resistsFullBlast(Block block) {
        MaterialTable.Material material = MaterialTable.lookup(block.defaultBlockState());
        return material.breakMode() == MaterialTable.BreakMode.NONE
                || material.breakMode() == MaterialTable.BreakMode.CUMULATIVE && material.breakDamage() > GrenadeTuning.HE_DAMAGE;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** Server-side player tick (spawn immunity and hurt cooldown count down here; mock players are not ticked). */
    private static void tickPlayer(ServerPlayer player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.tick();
        }
    }

    private static void setBlockBreaking(GameTestHelper helper, boolean enabled) {
        helper.getLevel().getGameRules().getRule(BlockBreakPolicy.BLOCK_BREAKING_RULE)
                .set(enabled, helper.getLevel().getServer());
    }

    private static Husk husk(GameTestHelper helper, Vec3 relative) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, relative);
        husk.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return husk;
    }

    private static GrenadeProjectile grenade(GameTestHelper helper, GrenadeKind kind, Vec3 relative, Vec3 velocity,
            @Nullable ServerPlayer owner) {
        ServerLevel level = helper.getLevel();
        GrenadeProjectile grenade = new GrenadeProjectile(GrenadeRegistry.PROJECTILE, level);
        grenade.setItem(new ItemStack(GrenadeRegistry.item(kind)));
        if (owner != null) {
            grenade.setOwner(owner);
        }
        Vec3 absolute = helper.absoluteVec(relative);
        grenade.moveTo(absolute.x, absolute.y, absolute.z);
        grenade.setDeltaMovement(velocity);
        level.addFreshEntity(grenade);
        return grenade;
    }

    private static AABB area(GameTestHelper helper) {
        return new AABB(helper.absoluteVec(new Vec3(-1.0, -1.0, -1.0)), helper.absoluteVec(new Vec3(9.0, 9.0, 9.0)));
    }

    private static List<GrenadeProjectile> projectiles(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(GrenadeProjectile.class, area(helper), Entity::isAlive);
    }

    private static List<GrenadeCloud> clouds(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(GrenadeCloud.class, area(helper), Entity::isAlive);
    }

    private static GrenadeEffects.BlastHit hit(GameTestHelper helper, List<GrenadeEffects.BlastHit> hits, Entity entity) {
        GrenadeEffects.BlastHit hit = hits.stream().filter(h -> h.entity() == entity).findFirst().orElse(null);
        helper.assertTrue(hit != null, "expected a blast hit on " + entity + ", got " + hits);
        return hit;
    }

    private static void look(ServerPlayer player, float yaw) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0F);
    }

    /** Mock player with its own profile name (scoreboard teams key on names), facing +X. */
    private static ServerPlayer player(GameTestHelper helper, String name, GameType mode, Vec3 relative) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(mode);
        Vec3 position = helper.absoluteVec(relative);
        player.teleportTo(level, position.x, position.y, position.z, -90.0F, 0.0F);
        look(player, -90.0F);
        player.setNoGravity(true);
        return player;
    }

    private static void remove(ServerPlayer player) {
        if (!player.hasDisconnected() && player.server.getPlayerList().getPlayer(player.getUUID()) != null) {
            player.server.getPlayerList().remove(player);
        }
        player.discard();
    }
}
