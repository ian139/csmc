package dev.csmc.ballistics;

import com.mojang.authlib.GameProfile;
import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.item.GunItem;
import dev.csmc.network.GunActionPayload;
import dev.csmc.network.GunSoundPayload;
import dev.csmc.network.ScopeStatePayload;
import dev.csmc.server.GunSystem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Test-only registered guns and datapacks keep scope behavior independent of the local CS2 cache. */
public final class ScopeGameTests implements FabricGameTest, ModInitializer {
    private static GunItem one;
    private static GunItem two;

    @Override
    public void onInitialize() {
        one = gun("scope_one");
        two = gun("scope_two");
    }

    private static GunItem gun(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("csmc-test", name);
        return Registry.register(BuiltInRegistries.ITEM, id, new GunItem(id));
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void manualSniperZoomSendsOneSoundPerAcceptedLevel(GameTestHelper helper) {
        Probe probe = probe(helper);
        ServerPlayer player = probe.player();
        try {
            for (String weapon : List.of("awp", "ssg08")) {
                ItemStack stack = equip(player, CsmcMod.GUNS.get(weapon));
                ItemStack reserve = new ItemStack(CsmcMod.SNIPER_AMMO, 17);
                player.getInventory().setItem(2, reserve);
                int magazine = stack.getOrDefault(CsmcMod.MAGAZINE, 0);
                GunSystem.handle(player, GunActionPayload.RELEASE);
                helper.assertTrue(probe.drainScopeSounds().isEmpty(), weapon + ": initial binding is silent");
                for (int level : new int[]{1, 2, 0}) {
                    GunSystem.handle(player, GunActionPayload.ZOOM);
                    helper.assertValueEqual(GunSystem.scopeLevel(player), level, weapon + ": accepted scope level");
                    List<GunSoundPayload> sent = probe.drainScopeSounds();
                    helper.assertValueEqual(sent.size(), 1, weapon + ": exactly one manual scope sound");
                    GunSoundPayload sound = sent.getFirst();
                    helper.assertValueEqual(sound.event(), level == 0
                            ? GunSoundPayload.ZOOM_OUT : GunSoundPayload.ZOOM_IN, weapon + ": scope sound direction");
                    helper.assertValueEqual(sound.weapon(), weapon, "scope sound weapon");
                    helper.assertValueEqual(sound.shooter(), player.getId(), "scope sound owner");
                    helper.assertValueEqual(stack.getOrDefault(CsmcMod.MAGAZINE, 0), magazine, "zoom keeps magazine");
                    helper.assertValueEqual(reserve.getCount(), 17, "zoom keeps reserve");
                }
            }
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void bindingReloadAndRejectedZoomDoNotSendScopeSounds(GameTestHelper helper) {
        Probe probe = probe(helper);
        ServerPlayer player = probe.player();
        try {
            for (String weapon : List.of("awp", "ssg08")) {
                equip(player, CsmcMod.GUNS.get(weapon));
                GunSystem.handle(player, GunActionPayload.ZOOM);
                helper.assertValueEqual(probe.drainScopeSounds().size(), 1, "manual scope before switching");
                ItemStack replacement = equip(player, CsmcMod.GUNS.get(weapon));
                GunSystem.handle(player, GunActionPayload.RELEASE);
                helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "replacement clears scope");
                helper.assertTrue(probe.drainScopeSounds().isEmpty(), "replacement binding is silent");
                GunSystem.handle(player, GunActionPayload.ZOOM);
                probe.drainScopeSounds();
                replacement.set(CsmcMod.MAGAZINE, 0);
                player.getInventory().setItem(2, new ItemStack(CsmcMod.SNIPER_AMMO, 17));
                GunSystem.handle(player, GunActionPayload.RELOAD);
                GunSystem.handle(player, GunActionPayload.ZOOM);
                helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "reload rejects zoom");
                helper.assertTrue(probe.drainScopeSounds().isEmpty(), "reload unzoom and rejected zoom are silent");
            }
            equip(player, two);
            GunSystem.handle(player, GunActionPayload.ZOOM);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "other scoped gun still accepts zoom");
            helper.assertTrue(probe.drainScopeSounds().isEmpty(), "other scoped gun has no sniper scope sound");
            equip(player, CsmcMod.GLOCK);
            GunSystem.handle(player, GunActionPayload.ZOOM);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "unscoped gun rejects zoom");
            helper.assertTrue(probe.drainScopeSounds().isEmpty(), "invalid zoom is silent");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void sniperBoltUnzoomAndAutomaticRestoreDoNotSendScopeSounds(GameTestHelper helper) {
        List<Probe> probes = List.of(probe(helper), probe(helper));
        int restoreDelay = 0;
        for (int i = 0; i < probes.size(); i++) {
            Probe probe = probes.get(i);
            GunItem gun = CsmcMod.GUNS.get(i == 0 ? "awp" : "ssg08");
            equip(probe.player(), gun);
            WeaponData data = WeaponRegistry.get(gun.weaponId());
            helper.assertTrue(data.zoom().unzoomAfterShot(), "sniper fixture has a bolt unzoom");
            restoreDelay = Math.max(restoreDelay, (int) Math.ceil(data.activeCycleTimeS(true) * 20.0) + 4);
            GunSystem.handle(probe.player(), GunActionPayload.ZOOM);
            GunSystem.handle(probe.player(), GunActionPayload.ZOOM);
            helper.assertValueEqual(probe.drainScopeSounds().size(), 2, "manual zooms before firing");
            GunSystem.handle(probe.player(), GunActionPayload.PRESS);
        }
        helper.runAfterDelay(2, () -> {
            for (Probe probe : probes) {
                GunSystem.handle(probe.player(), GunActionPayload.RELEASE);
                helper.assertValueEqual(GunSystem.shotSequence(probe.player()), 1, "server accepted bolt shot");
                helper.assertValueEqual(GunSystem.scopeLevel(probe.player()), 0, "bolt shot unzoomed");
                helper.assertTrue(probe.drainScopeSounds().isEmpty(), "bolt unzoom is silent");
            }
        });
        helper.runAfterDelay(restoreDelay, () -> {
            try {
                for (Probe probe : probes) {
                    helper.assertValueEqual(GunSystem.scopeLevel(probe.player()), 2, "previous level restored");
                    helper.assertTrue(probe.drainScopeSounds().isEmpty(), "automatic rezoom is silent");
                }
                helper.succeed();
            } finally {
                for (Probe probe : probes) remove(probe.player());
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void cyclesTwoLevelsAndAppliesOnlyScopedSpeed(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        equip(player, two);
        WeaponData data = WeaponRegistry.get(two.weaponId());
        double baseSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        helper.assertValueEqual(data.zoom().levelCount(), 2, "two-level fixture");
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "first zoom");
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED)
                - baseSpeed * data.activeMaxSpeed(true) / data.maxSpeed()) < 1.0E-8, "scoped movement ratio");
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 2, "second zoom");
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "cycle back to hip fire");
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED) - baseSpeed) < 1.0E-8,
                "unzoom removes the transient slowdown");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void cyclesOneLevelAndLeavesUnscopedGunsAlone(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        equip(player, one);
        helper.assertValueEqual(WeaponRegistry.get(one.weaponId()).zoom().levelCount(), 1, "one-level fixture");
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "single zoom");
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "single zoom wraps to hip fire");
        equip(player, CsmcMod.GLOCK);
        double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "non-scoped gun ignores zoom");
        helper.assertValueEqual(player.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "non-scoped speed");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void deathImmediatelyRemovesActiveScopedSlowdown(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        equip(player, two);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(CsmcMod.id("scope_speed")) != null,
                "scope slowdown exists before death");
        player.kill();
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "death clears scope");
        helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(CsmcMod.id("scope_speed")) == null,
                "death event removes slowdown before respawn can copy inventory");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void explicitZoomCancelsPendingBoltRestore(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        equip(player, two);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.PRESS);
        helper.runAfterDelay(2, () -> {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "bolt shot unzoomed");
            GunSystem.handle(player, GunActionPayload.ZOOM);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "explicit intent chooses first level");
        });
        helper.runAfterDelay(10, () -> {
            helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "old second level is not restored over explicit intent");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void reloadIntentUnzoomsEvenWhenFullOrWithoutAmmo(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack stack = equip(player, two);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.RELOAD);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "full-magazine reload intent unzooms");
        stack.set(CsmcMod.MAGAZINE, 0);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.RELOAD);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "no-ammo reload intent unzooms");
        player.getInventory().setItem(2, new ItemStack(CsmcMod.RIFLE_AMMO, 32));
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.RELOAD);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "accepted reload unzooms");
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "cannot zoom during reload");
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(stack.getOrDefault(CsmcMod.MAGAZINE, 0), 8, "reload completes through server tick");
            GunSystem.handle(player, GunActionPayload.ZOOM);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "can zoom after reload");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void slotStackAndDropClearScope(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        equip(player, two);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        player.getInventory().selected = 1;
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "slot change clears scope");
            player.getInventory().selected = 0;
            GunSystem.handle(player, GunActionPayload.ZOOM);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "redraw starts at first level");
            equip(player, two);
        });
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "same-type stack replacement clears scope");
            GunSystem.handle(player, GunActionPayload.ZOOM);
            player.drop(true);
        });
        helper.runAfterDelay(6, () -> {
            helper.assertTrue(player.getMainHandItem().isEmpty(), "gun was actually dropped");
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "drop clears scope");
            helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(CsmcMod.id("scope_speed")) == null,
                    "drop removes scoped speed modifier");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void acceptedBoltShotRestoresPreviousLevelOnlyAfterScopedCycle(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack stack = equip(player, two);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.PRESS);
        helper.runAfterDelay(2, () -> {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            helper.assertValueEqual(stack.getOrDefault(CsmcMod.MAGAZINE, 0), 7, "accepted shot consumed one round");
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "bolt shot unzooms before cycle finishes");
            helper.assertTrue(ShooterState.of(player).accuracyPenalty > 0.019
                    && ShooterState.of(player).accuracyPenalty < 0.021, "accepted scoped shot uses scoped fire penalty");
            GunSystem.handle(player, GunActionPayload.PRESS);
            GunSystem.handle(player, GunActionPayload.RELEASE);
        });
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0,
                    "scoped six-tick cycle, not the one-tick hip-fire cycle, gates restoration");
            helper.assertValueEqual(stack.getOrDefault(CsmcMod.MAGAZINE, 0), 7,
                    "another press cannot bypass the accepted scoped cycle");
        });
        helper.runAfterDelay(10, () -> {
            helper.assertValueEqual(GunSystem.scopeLevel(player), 2, "previous second zoom level restored");
            stack.set(CsmcMod.MAGAZINE, 0);
            GunSystem.handle(player, GunActionPayload.PRESS);
        });
        helper.runAfterDelay(12, () -> {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 2, "dry fire does not unzoom");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void boltRestoreIsCancelledByReloadSwitchReplacementDropAndDeath(GameTestHelper helper) {
        ServerPlayer[] players = new ServerPlayer[5];
        for (int i = 0; i < players.length; i++) {
            players[i] = player(helper);
            equip(players[i], two);
            GunSystem.handle(players[i], GunActionPayload.ZOOM);
            GunSystem.handle(players[i], GunActionPayload.PRESS);
        }
        helper.runAfterDelay(2, () -> {
            for (ServerPlayer player : players) {
                GunSystem.handle(player, GunActionPayload.RELEASE);
                helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "shot unzooms");
            }
            GunSystem.handle(players[0], GunActionPayload.RELOAD);
            players[1].getInventory().selected = 1;
            equip(players[2], two);
            players[3].drop(true);
            players[4].setHealth(0.0F);
        });
        helper.runAfterDelay(4, () -> players[1].getInventory().selected = 0);
        helper.runAfterDelay(12, () -> {
            for (int i = 0; i < players.length; i++) {
                helper.assertValueEqual(GunSystem.scopeLevel(players[i]), 0, "restore cancelled for case " + i);
                helper.assertTrue(players[i].getAttribute(Attributes.MOVEMENT_SPEED)
                        .getModifier(CsmcMod.id("scope_speed")) == null, "cancel removes slowdown for case " + i);
                remove(players[i]);
            }
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void dimensionTransitionClearsActiveScopeForSameStack(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack stack = equip(player, two);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 2, "scoped before transition");
        ServerLevel target = transition(helper, player);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "old-world zoom is not authoritative in the new world");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(player.serverLevel() == target, "player stayed in the new world");
            helper.assertTrue(player.getMainHandItem() == stack && player.getInventory().selected == 0,
                    "same stack and slot survive the transition");
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "world change clears scope");
            helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(CsmcMod.id("scope_speed")) == null,
                    "world change removes scoped speed modifier");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void dimensionTransitionCancelsPendingBoltRestore(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack stack = equip(player, two);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.PRESS);
        ServerLevel[] target = new ServerLevel[1];
        helper.runAfterDelay(2, () -> {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            helper.assertValueEqual(stack.getOrDefault(CsmcMod.MAGAZINE, 0), 7, "accepted bolt shot");
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "bolt shot unzoomed with a pending restore");
            target[0] = transition(helper, player);
        });
        helper.runAfterDelay(14, () -> {
            helper.assertTrue(player.serverLevel() == target[0], "player stayed in the new world");
            helper.assertTrue(player.getMainHandItem() == stack, "same stack survives the transition");
            helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "pending bolt rezoom is not restored after world change");
            helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(CsmcMod.id("scope_speed")) == null,
                    "no scoped speed modifier after cancelled restore");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void scopesAgainFromHipFireAfterDimensionTransition(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack stack = equip(player, two);
        WeaponData data = WeaponRegistry.get(two.weaponId());
        GunSystem.handle(player, GunActionPayload.ZOOM);
        GunSystem.handle(player, GunActionPayload.ZOOM);
        ServerLevel target = transition(helper, player);
        double baseSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
        // Handled before any server tick: the intent itself must rebind, so level 2 cannot wrap to hip fire.
        GunSystem.handle(player, GunActionPayload.ZOOM);
        helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "new-world zoom starts at the first level");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(player.serverLevel() == target && player.getMainHandItem() == stack,
                    "same stack held in the new world");
            helper.assertValueEqual(GunSystem.scopeLevel(player), 1, "new-world scope persists across ticks");
            helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    - baseSpeed * data.activeMaxSpeed(true) / data.maxSpeed()) < 1.0E-8, "new-world scoped movement ratio");
            GunSystem.handle(player, GunActionPayload.ZOOM);
            helper.assertValueEqual(GunSystem.scopeLevel(player), 2, "second level available in the new world");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void realServerShotsUseScopedAccuracyAndPreserveHugeHipFirePenalty(GameTestHelper helper) {
        ServerPlayer scoped = player(helper);
        ServerPlayer unscoped = player(helper);
        Vec3 hipPosition = unscoped.position().add(4.0, 0.0, 0.0);
        unscoped.teleportTo(hipPosition.x, hipPosition.y, hipPosition.z);
        scoped.setXRot(0);
        scoped.setYRot(0);
        unscoped.setXRot(0);
        unscoped.setYRot(0);
        equip(scoped, one);
        equip(unscoped, one);
        WeaponData data = WeaponRegistry.get(one.weaponId());
        ShooterState scopedState = ShooterState.of(scoped);
        ShooterState hipState = ShooterState.of(unscoped);
        scoped.setOnGround(true);
        unscoped.setOnGround(true);
        helper.assertTrue(Ballistics.stanceInaccuracy(unscoped, hipState, data, false) >= 10.0,
                "huge sniper hip-fire inaccuracy is not clamped");
        helper.assertValueEqual(Ballistics.stanceInaccuracy(scoped, scopedState, data, true), 0.0, "scoped stance");
        scopedState.horizontalSpeed = 0.13;
        helper.assertValueEqual(Ballistics.stanceInaccuracy(scoped, scopedState, data, true), 0.5,
                "movement inaccuracy ramps using the lower scoped max speed");
        scopedState.horizontalSpeed = 0.0;
        Vec3 aim = new Vec3(0, 0, 1);
        helper.assertTrue(Ballistics.applySpread(aim, data.activeInaccuracy(false).stand(), data.activeSpreadBase(false),
                RandomSource.create(42)).distanceTo(aim) > 0.1, "hip-fire spread remains large");
        helper.assertTrue(Ballistics.applySpread(aim, data.activeInaccuracy(true).stand(), data.activeSpreadBase(true),
                RandomSource.create(42)).distanceTo(aim) < 1.0E-9, "scoped spread selects the accurate mode");
        var target = helper.spawn(EntityType.HUSK, new BlockPos(0, 4, 6));
        target.setNoAi(true);
        target.setNoGravity(true);
        Vec3 targetPosition = scoped.position().add(0.0, 0.0, 6.0);
        target.moveTo(targetPosition.x, targetPosition.y, targetPosition.z, 0.0F, 0.0F);
        float health = target.getHealth();
        GunSystem.handle(scoped, GunActionPayload.ZOOM);
        GunSystem.handle(scoped, GunActionPayload.PRESS);
        GunSystem.handle(unscoped, GunActionPayload.PRESS);
        helper.runAfterDelay(2, () -> {
            GunSystem.handle(scoped, GunActionPayload.RELEASE);
            GunSystem.handle(unscoped, GunActionPayload.RELEASE);
            helper.assertTrue(target.getHealth() < health, "scoped shot hit through real server GunSystem/Ballistics path");
            helper.assertTrue(scopedState.accuracyPenalty > 0.019 && scopedState.accuracyPenalty < 0.021,
                    "scoped shot adds scoped fire inaccuracy");
            helper.assertTrue(hipState.accuracyPenalty > 3.99, "hip-fire shot preserves its large fire inaccuracy");
            helper.assertValueEqual(GunSystem.scopeLevel(scoped), 1, "non-bolt scoped shot stays zoomed");
            target.discard();
            remove(scoped);
            remove(unscoped);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void scopedMainGunBlocksVanillaUseForEitherHand(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        equip(player, one);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STONE));
        BlockHitResult hit = new BlockHitResult(player.position(), Direction.UP, player.blockPosition(), false);
        var target = helper.spawn(EntityType.COW, new BlockPos(0, 4, 2));
        for (InteractionHand hand : InteractionHand.values()) {
            helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, player.level(), hand, hit),
                    InteractionResult.FAIL, "scope gun suppresses vanilla block interaction for " + hand);
            helper.assertValueEqual(UseItemCallback.EVENT.invoker().interact(player, player.level(), hand).getResult(),
                    InteractionResult.FAIL, "scope gun suppresses vanilla use for " + hand);
            helper.assertValueEqual(UseEntityCallback.EVENT.invoker().interact(player, player.level(), hand, target, null),
                    InteractionResult.FAIL, "scope gun suppresses vanilla entity interaction for " + hand);
        }
        helper.assertValueEqual(GunSystem.scopeLevel(player), 0, "vanilla callbacks never trigger a second zoom");
        equip(player, CsmcMod.GLOCK);
        helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, player.level(),
                InteractionHand.MAIN_HAND, hit), InteractionResult.PASS, "non-scoped block use unaffected");
        helper.assertValueEqual(UseItemCallback.EVENT.invoker().interact(player, player.level(),
                InteractionHand.MAIN_HAND).getResult(), InteractionResult.PASS, "non-scoped item use unaffected");
        target.discard();
        remove(player);
        helper.succeed();
    }

    /** Captures real outgoing sound packets, using the connected-player fixture used by equipment/glove tests. */
    private record Probe(ServerPlayer player, EmbeddedChannel channel) {
        List<GunSoundPayload> drainScopeSounds() {
            List<GunSoundPayload> sent = new ArrayList<>();
            Object message;
            while ((message = channel.readOutbound()) != null) {
                if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload() instanceof GunSoundPayload payload
                        && (payload.event() == GunSoundPayload.ZOOM_IN || payload.event() == GunSoundPayload.ZOOM_OUT)) {
                    sent.add(payload);
                }
            }
            return sent;
        }
    }

    @SuppressWarnings("unchecked")
    private static Probe probe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "scope" + level.getRandom().nextInt(100000)), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        try {
            Field field = AbstractChanneledNetworkAddon.class.getDeclaredField("sendableChannels");
            field.setAccessible(true);
            Set<ResourceLocation> channels = (Set<ResourceLocation>) field.get(ServerNetworkingImpl.getAddon(player.connection));
            channels.add(GunSoundPayload.TYPE.id());
            channels.add(ScopeStatePayload.TYPE.id());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        player.setGameMode(GameType.SURVIVAL);
        Vec3 at = helper.absoluteVec(new Vec3(0.5, 4.0, 0.5));
        player.teleportTo(level, at.x, at.y, at.z, 0.0F, -90.0F);
        player.setNoGravity(true);
        player.getInventory().clearContent();
        Probe probe = new Probe(player, channel);
        probe.drainScopeSounds();
        return probe;
    }

    private static ItemStack equip(ServerPlayer player, GunItem gun) {
        ItemStack stack = new ItemStack(gun);
        stack.set(CsmcMod.MAGAZINE, WeaponRegistry.get(gun.weaponId()).magazineSize());
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, stack);
        return stack;
    }

    /** Real player dimension change into another loaded world, keeping the held stack and selected slot. */
    private static ServerLevel transition(GameTestHelper helper, ServerPlayer player) {
        ServerLevel origin = player.serverLevel();
        ServerLevel target = player.server.getLevel(Level.NETHER);
        if (target == null || target == origin) {
            target = null;
            for (ServerLevel level : player.server.getAllLevels()) {
                if (level != origin) {
                    target = level;
                    break;
                }
            }
        }
        helper.assertTrue(target != null, "an alternate loaded world exists");
        // Above the nether roof so the mock player is not left inside terrain.
        var moved = player.changeDimension(new DimensionTransition(target, new Vec3(0.5, 200.0, 0.5), Vec3.ZERO,
                player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING));
        helper.assertTrue(moved == player && player.serverLevel() == target, "player changed world");
        return target;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(0.5, 4.0, 0.5));
        player.teleportTo(position.x, position.y, position.z);
        player.setNoGravity(true);
        player.setXRot(-90.0F);
        return player;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
