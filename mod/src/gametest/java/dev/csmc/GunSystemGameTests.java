package dev.csmc;

import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.item.GunItem;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.network.GunActionPayload;
import dev.csmc.network.GunSoundPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import dev.csmc.server.GunSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public final class GunSystemGameTests implements FabricGameTest {
    /**
     * Every registered firearm item resolves to a valid weapon definition with its own id and a usable ammo
     * item, whatever mix of placeholder, datapack and local-cache data supplied it.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void everyFirearmHasValidDefinition(GameTestHelper helper) {
        helper.assertValueEqual(CsmcMod.GUNS.size(), CsmcMod.FIREARM_IDS.size(), "registered firearm count");
        for (String name : CsmcMod.FIREARM_IDS) {
            GunItem gun = CsmcMod.GUNS.get(name);
            helper.assertTrue(gun != null, "missing gun item " + name);
            helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(gun), CsmcMod.id(name), "item id of " + name);
            helper.assertValueEqual(gun.weaponId(), CsmcMod.id(name), "weapon id of " + name);
            WeaponData data = WeaponRegistry.get(gun.weaponId());
            helper.assertTrue(data != null, "missing weapon definition for " + name + "; errors " + WeaponRegistry.lastErrors());
            helper.assertValueEqual(data.id(), gun.weaponId(), "definition id of " + name);
            helper.assertTrue(data.magazineSize() >= 1 && data.damage() > 0.0 && data.cycleTimeS() > 0.0
                    && data.pellets() >= 1 && !data.displayName().isBlank(), "invalid stats for " + name + ": " + data);
            helper.assertTrue(CsmcMod.ammo(data.ammoType()) != Items.AIR,
                    "ammo type '" + data.ammoType() + "' of " + name + " has no item");
        }
        for (Map.Entry<String, GunItem> entry : CsmcMod.GUNS.entrySet()) {
            helper.assertTrue(CsmcMod.FIREARM_IDS.contains(entry.getKey()), "unlisted gun " + entry.getKey());
        }
        // The balance harness (BalanceInvariantTest) evaluates exactly the WeaponRole ids.
        helper.assertValueEqual(java.util.List.copyOf(dev.csmc.data.WeaponRole.all().keySet()).stream().sorted().toList(),
                CsmcMod.FIREARM_IDS.stream().sorted().toList(), "every firearm has exactly one balance role");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void everyNewFirearmInitializesToEffectiveCapacityInInventory(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        List<ItemStack> guns = new ArrayList<>();
        SkinData skin = new SkinData(920001, 0.3f, 5);
        for (String id : CsmcMod.FIREARM_IDS) {
            ItemStack gun = new ItemStack(CsmcMod.GUNS.get(id));
            helper.assertTrue(!gun.has(CsmcMod.MAGAZINE), id + " starts uninitialized");
            gun.set(SkinGameplay.SKIN, skin);
            player.getInventory().setItem(guns.size(), gun);
            guns.add(gun);
        }
        ItemStack reserve = new ItemStack(CsmcMod.PISTOL_AMMO, 64);
        player.getInventory().setItem(40, reserve);
        player.getInventory().selected = 0;
        // Mock server players do not run normal inventory ticks automatically.
        player.getInventory().tick();
        helper.runAfterDelay(2, () -> {
            player.getInventory().tick();
            for (ItemStack gun : guns) {
                GunItem item = (GunItem) gun.getItem();
                helper.assertValueEqual(gun.getOrDefault(CsmcMod.MAGAZINE, -1),
                        WeaponRegistry.get(item.weaponId()).magazineSize(), item.weaponId() + " effective capacity");
                helper.assertValueEqual(gun.get(SkinGameplay.SKIN), skin, item.weaponId() + " skin preserved");
            }
            helper.assertValueEqual(reserve.getCount(), 64, "initial magazines do not consume reserve");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void firstTriggerInitializesBeforeInventoryTick(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack gun = new ItemStack(CsmcMod.GLOCK);
        WeaponData data = WeaponRegistry.get(CsmcMod.GLOCK.weaponId());
        ItemStack reserve = new ItemStack(CsmcMod.ammo(data.ammoType()), 64);
        player.getInventory().setItem(0, gun);
        player.getInventory().setItem(2, reserve);
        player.getInventory().selected = 0;
        GunSystem.handle(player, GunActionPayload.PRESS);
        helper.assertValueEqual(gun.getOrDefault(CsmcMod.MAGAZINE, -1), data.magazineSize(),
                "first input initializes the authoritative magazine immediately");
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(gun.getOrDefault(CsmcMod.MAGAZINE, -1), data.magazineSize() - 1,
                    "first trigger fires from a full magazine");
            helper.assertValueEqual(reserve.getCount(), 64, "firing consumes no reserve rounds");
            GunSystem.handle(player, GunActionPayload.RELEASE);
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void explicitEmptyAndPartialMagazinesSurviveTicksAndSelection(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack empty = new ItemStack(CsmcMod.GLOCK);
        empty.set(CsmcMod.MAGAZINE, 0);
        ItemStack partial = new ItemStack(CsmcMod.AK47);
        int rounds = WeaponRegistry.get(CsmcMod.AK47.weaponId()).magazineSize() - 1;
        partial.set(CsmcMod.MAGAZINE, rounds);
        player.getInventory().setItem(0, empty);
        player.getInventory().setItem(1, partial);
        player.getInventory().selected = 0;
        GunSystem.handle(player, GunActionPayload.PRESS);
        player.getInventory().tick();
        helper.runAfterDelay(2, () -> {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            player.getInventory().selected = 1;
            GunSystem.handle(player, GunActionPayload.RELEASE);
            SkinData skin = new SkinData(920001, 0.3f, 5);
            empty.set(SkinGameplay.SKIN, skin);
            partial.set(SkinGameplay.SKIN, skin);
            player.getInventory().tick();
        });
        helper.runAfterDelay(4, () -> {
            player.getInventory().selected = 0;
            GunSystem.handle(player, GunActionPayload.RELEASE);
            player.getInventory().tick();
        });
        helper.runAfterDelay(6, () -> {
            helper.assertValueEqual(empty.getOrDefault(CsmcMod.MAGAZINE, -1), 0,
                    "explicit empty magazine stays empty after dry fire, skin change and reselection");
            helper.assertValueEqual(partial.getOrDefault(CsmcMod.MAGAZINE, -1), rounds,
                    "partial magazine survives ticks, skin change and selection");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 300)
    public void reloadConsumesOnlyOnCompletionAndCancelsWhenSwitched(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        var data = WeaponRegistry.get(CsmcMod.id("glock"));
        helper.assertTrue(data != null, "Server datapack weapon registry must be loaded");
        ItemStack pistol = new ItemStack(CsmcMod.GLOCK);
        pistol.set(CsmcMod.MAGAZINE, 0);
        ItemStack ammo = new ItemStack(CsmcMod.ammo(data.ammoType()), 64);
        player.getInventory().setItem(0, pistol);
        player.getInventory().setItem(2, ammo);
        player.getInventory().selected = 0;
        long duration = Math.max(1, (long) Math.ceil(data.reloadTimeS() * 20));
        GunSystem.handle(player, GunActionPayload.RELOAD);
        helper.runAfterDelay(Math.max(1, duration - 2), () -> {
            helper.assertValueEqual(pistol.getOrDefault(CsmcMod.MAGAZINE, 0), 0, "Magazine before reload timer");
            helper.assertValueEqual(ammo.getCount(), 64, "Ammo before reload timer");
        });
        helper.runAfterDelay(duration + 2, () -> {
            helper.assertValueEqual(pistol.getOrDefault(CsmcMod.MAGAZINE, 0), data.magazineSize(), "Loaded magazine");
            helper.assertValueEqual(ammo.getCount(), 64 - data.magazineSize(), "Consumed inventory rounds");
            pistol.set(CsmcMod.MAGAZINE, 0);
            GunSystem.handle(player, GunActionPayload.RELOAD);
            player.getInventory().selected = 1;
        });
        helper.runAfterDelay(duration + 5, () -> player.getInventory().selected = 0);
        helper.runAfterDelay(duration * 2 + 8, () -> {
            helper.assertValueEqual(pistol.getOrDefault(CsmcMod.MAGAZINE, 0), 0, "Cancelled reload magazine");
            helper.assertValueEqual(ammo.getCount(), 64 - data.magazineSize(), "Cancelled reload consumes nothing");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 300)
    public void partialReloadTransfersMatchingRoundsAcrossStacks(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        WeaponData data = WeaponRegistry.get(CsmcMod.id("glock"));
        ItemStack gun = new ItemStack(CsmcMod.GLOCK);
        int capacity = data.magazineSize();
        gun.set(CsmcMod.MAGAZINE, capacity - 5);
        var ammoItem = CsmcMod.ammo(data.ammoType());
        ItemStack first = new ItemStack(ammoItem, 2);
        ItemStack second = new ItemStack(ammoItem, 9);
        ItemStack wrong = new ItemStack(ammoItem == CsmcMod.PISTOL_AMMO ? CsmcMod.RIFLE_AMMO : CsmcMod.PISTOL_AMMO, 64);
        player.getInventory().setItem(0, gun);
        player.getInventory().setItem(2, first);
        player.getInventory().setItem(3, second);
        player.getInventory().setItem(4, wrong);
        player.getInventory().selected = 0;
        long duration = Math.max(1, (long) Math.ceil(data.reloadTimeS() * 20));
        GunSystem.handle(player, GunActionPayload.RELOAD);
        helper.runAfterDelay(duration + 2, () -> {
            helper.assertValueEqual(gun.getOrDefault(CsmcMod.MAGAZINE, 0), capacity, "partial magazine gains five rounds");
            helper.assertValueEqual(first.getCount(), 0, "first matching stack exhausted");
            helper.assertValueEqual(second.getCount(), 6, "second matching stack loses only remaining three rounds");
            helper.assertValueEqual(wrong.getCount(), 64, "other ammo type is unchanged");
            gun.set(CsmcMod.MAGAZINE, capacity - 5);
            second.setCount(3);
            GunSystem.handle(player, GunActionPayload.RELOAD);
        });
        helper.runAfterDelay(duration * 2 + 5, () -> {
            helper.assertValueEqual(gun.getOrDefault(CsmcMod.MAGAZINE, 0), capacity - 2, "limited reserve loads only three rounds");
            helper.assertValueEqual(second.getCount(), 0, "limited reserve exhausted");
            helper.assertValueEqual(wrong.getCount(), 64, "wrong ammunition cannot fill the gap");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void triggerIntentCannotBypassServerCooldownAndAutomaticRepeats(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack pistol = new ItemStack(CsmcMod.GLOCK);
        int capacity = WeaponRegistry.get(CsmcMod.id("glock")).magazineSize();
        pistol.set(CsmcMod.MAGAZINE, capacity);
        player.getInventory().setItem(0, pistol);
        player.getInventory().selected = 0;
        for (int i = 0; i < 50; i++) {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            GunSystem.handle(player, GunActionPayload.PRESS);
        }
        helper.runAfterDelay(20, () -> {
            helper.assertValueEqual(pistol.getOrDefault(CsmcMod.MAGAZINE, 0), capacity - 1, "Held semi-auto fires exactly once despite input spam");
            GunSystem.handle(player, GunActionPayload.RELEASE);
            WeaponData ak = WeaponRegistry.get(CsmcMod.id("ak47"));
            ItemStack rifle = new ItemStack(CsmcMod.AK47);
            rifle.set(CsmcMod.MAGAZINE, ak.magazineSize());
            player.getInventory().setItem(0, rifle);
            GunSystem.handle(player, GunActionPayload.PRESS);
            helper.runAfterDelay(20, () -> {
                int fired = ak.magazineSize() - rifle.getOrDefault(CsmcMod.MAGAZINE, 0);
                // 20 ticks of held trigger: at least two shots, never faster than one per cycle.
                int maxShots = (int) Math.floor(21.0 / Math.max(1.0, ak.cycleTimeS() * 20.0)) + 1;
                helper.assertTrue(ak.automatic() && fired >= 2 && fired <= maxShots,
                        "Held automatic trigger must repeat at bounded server cadence, fired=" + fired + ", max=" + maxShots);
                GunSystem.handle(player, GunActionPayload.RELEASE);
                remove(player);
                helper.succeed();
            });
        });
    }

    /** Shot and reload data the viewmodel needs survives the wire format, including the -1 "no magazine" marker. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void gunSoundPayloadCarriesShotAndReloadData(GameTestHelper helper) {
        for (GunSoundPayload sent : new GunSoundPayload[]{
                new GunSoundPayload("glock", GunSoundPayload.FIRE, 1, 2, 3, 7, 0, 41, 0, 0),
                new GunSoundPayload("nova", GunSoundPayload.RELOAD_START, 1, 2, 3, 7, 60, 0, 3, 5),
                new GunSoundPayload("ak47", GunSoundPayload.DRAW, 1, 2, 3, 7, 0, 0, -1, 0)}) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            GunSoundPayload.STREAM_CODEC.encode(buf, sent);
            helper.assertValueEqual(GunSoundPayload.STREAM_CODEC.decode(buf), sent, "round trip");
            helper.assertValueEqual(buf.readableBytes(), 0, "no trailing bytes");
        }
        helper.succeed();
    }

    /** Each accepted shot advances the shooter's sequence once; dry fire and refused presses do not. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void shotsAdvanceTheShooterSequence(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack pistol = new ItemStack(CsmcMod.GLOCK);
        pistol.set(CsmcMod.MAGAZINE, 2);
        player.getInventory().setItem(0, pistol);
        player.getInventory().selected = 0;
        int before = GunSystem.shotSequence(player);
        GunSystem.handle(player, GunActionPayload.PRESS);
        helper.runAfterDelay(10, () -> {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            GunSystem.handle(player, GunActionPayload.PRESS);
        });
        helper.runAfterDelay(20, () -> {
            GunSystem.handle(player, GunActionPayload.RELEASE);
            GunSystem.handle(player, GunActionPayload.PRESS); // empty: dry fire
        });
        helper.runAfterDelay(30, () -> {
            helper.assertValueEqual(pistol.getOrDefault(CsmcMod.MAGAZINE, 0), 0, "both rounds fired");
            helper.assertValueEqual(GunSystem.shotSequence(player) - before, 2, "one sequence step per shot");
            GunSystem.handle(player, GunActionPayload.RELEASE);
            remove(player);
            helper.succeed();
        });
    }

    /** The shell count announced at reload start is exactly what the reload loads (Nova: one shell vs five). */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
    public void announcedReloadRoundsMatchRoundsLoaded(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        WeaponData data = WeaponRegistry.get(CsmcMod.id("nova"));
        ItemStack gun = new ItemStack(CsmcMod.GUNS.get("nova"));
        int capacity = data.magazineSize();
        gun.set(CsmcMod.MAGAZINE, capacity - 1);
        ItemStack ammo = new ItemStack(CsmcMod.ammo(data.ammoType()), 64);
        player.getInventory().setItem(0, gun);
        player.getInventory().setItem(2, ammo);
        player.getInventory().selected = 0;
        long duration = Math.max(1, (long) Math.ceil(data.reloadTimeS() * 20));
        helper.assertValueEqual(GunSystem.roundsToLoad(capacity - 1, capacity, 64), 1, "one shell missing");
        helper.assertValueEqual(GunSystem.roundsToLoad(capacity - 5, capacity, 64), 5, "five shells missing");
        helper.assertValueEqual(GunSystem.roundsToLoad(capacity - 5, capacity, 2), 2, "reserve-limited");
        GunSystem.handle(player, GunActionPayload.RELOAD);
        helper.runAfterDelay(duration + 2, () -> {
            helper.assertValueEqual(64 - ammo.getCount(), 1, "one shell loaded");
            gun.set(CsmcMod.MAGAZINE, capacity - 5);
            GunSystem.handle(player, GunActionPayload.RELOAD);
        });
        helper.runAfterDelay(duration * 2 + 5, () -> {
            helper.assertValueEqual(63 - ammo.getCount(), 5, "five shells loaded");
            remove(player);
            helper.succeed();
        });
    }

    /** Wrong categories never reload; mixed reserves and the HUD's counter use the same item mapping. */
    // Waits two of the longest reloads: the Zeus recharge (data/csmc/weapon_balance/taser.json, 30 s).
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1400)
    public void everyWeaponReloadsOnlyItsCategoryAndCountsMatchingHudReserve(GameTestHelper helper) {
        record Scenario(String id, ServerPlayer player, ItemStack gun, WeaponData data,
                        Item expected, List<ItemStack> wrong) {}
        List<Scenario> scenarios = new ArrayList<>();
        Item[] categories = {CsmcMod.PISTOL_AMMO, CsmcMod.RIFLE_AMMO,
                CsmcMod.SNIPER_AMMO, CsmcMod.SHOTGUN_SHELLS};
        Map<String, Item> expected = new java.util.HashMap<>();
        Map<String, Item> groups = Map.of(
                "glock hkp2000 usp_silencer p250 fiveseven tec9 cz75a deagle elite revolver mac10 mp9 mp7 mp5sd ump45 p90 bizon taser", CsmcMod.PISTOL_AMMO,
                "famas galilar ak47 m4a1 m4a1_silencer aug sg556 m14 m249 negev", CsmcMod.RIFLE_AMMO,
                "ssg08 awp scar20 g3sg1", CsmcMod.SNIPER_AMMO,
                "nova xm1014 mag7 sawedoff", CsmcMod.SHOTGUN_SHELLS);
        groups.forEach((ids, ammo) -> {
            for (String id : ids.split(" ")) expected.put(id, ammo);
        });
        long longest = 1;
        for (String id : CsmcMod.FIREARM_IDS) {
            WeaponData data = WeaponRegistry.get(CsmcMod.id(id));
            Item ammo = expected.get(id);
            helper.assertTrue(ammo != null, "test covers " + id);
            helper.assertValueEqual(CsmcMod.ammo(data.ammoType()), ammo, id + " ammo category");
            helper.assertValueEqual(CsmcMod.ammo(BuiltInRegistries.ITEM.getKey(ammo).toString()), ammo,
                    id + " accepts the item-id ammo definition");
            ServerPlayer player = player(helper);
            ItemStack gun = new ItemStack(CsmcMod.GUNS.get(id));
            gun.set(CsmcMod.MAGAZINE, Math.max(0, data.magazineSize() - 3));
            player.getInventory().setItem(0, gun);
            player.getInventory().selected = 0;
            List<ItemStack> wrong = new ArrayList<>();
            for (Item other : categories) {
                if (other == ammo) continue;
                ItemStack stack = new ItemStack(other, 11);
                player.getInventory().setItem(wrong.size() + 1, stack);
                wrong.add(stack);
            }
            helper.assertValueEqual(GunSystem.countAmmo(player, ammo), 0, id + " wrong-only HUD reserve");
            GunSystem.handle(player, GunActionPayload.RELOAD);
            scenarios.add(new Scenario(id, player, gun, data, ammo, wrong));
            longest = Math.max(longest, (long) Math.ceil(data.reloadTimeS() * 20));
        }
        long delay = longest + 3;
        helper.runAfterDelay(delay, () -> {
            for (Scenario scenario : scenarios) {
                helper.assertValueEqual(scenario.gun().getOrDefault(CsmcMod.MAGAZINE, 0),
                        Math.max(0, scenario.data().magazineSize() - 3), scenario.id() + " rejects wrong ammo");
                scenario.player().getInventory().setItem(20, new ItemStack(scenario.expected(), 1));
                // Offhand rounds are part of the same inventory reserve used by the HUD.
                scenario.player().getInventory().setItem(40, new ItemStack(scenario.expected(), 4));
                helper.assertValueEqual(GunSystem.countAmmo(scenario.player(), scenario.expected()), 5,
                        scenario.id() + " mixed-inventory HUD reserve");
                GunSystem.handle(scenario.player(), GunActionPayload.RELOAD);
            }
        });
        helper.runAfterDelay(delay * 2, () -> {
            for (Scenario scenario : scenarios) {
                helper.assertValueEqual(scenario.gun().getOrDefault(CsmcMod.MAGAZINE, 0),
                        scenario.data().magazineSize(), scenario.id() + " loads matching ammo across stacks");
                int loaded = Math.min(3, scenario.data().magazineSize());
                helper.assertValueEqual(GunSystem.countAmmo(scenario.player(), scenario.expected()), 5 - loaded,
                        scenario.id() + " HUD reserve after reload");
                for (ItemStack wrong : scenario.wrong()) {
                    helper.assertValueEqual(wrong.getCount(), 11, scenario.id() + " preserves other ammo");
                }
                remove(scenario.player());
            }
            helper.succeed();
        });
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(0.5, 4, 0.5));
        player.teleportTo(position.x, position.y, position.z);
        player.setXRot(-90);
        player.setYRot(0);
        return player;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
