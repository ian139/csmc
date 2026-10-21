package dev.csmc.skin;

import dev.csmc.CsmcMod;
import dev.csmc.skin.gameplay.CaseOpeningPayload;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.WeaponCaseItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** The Loadout Case only rolls skins for guns the opener carries; the Weapon Case stays unrestricted. */
public final class LoadoutCaseGameTests implements FabricGameTest {
    // Self-authored kits only: no catalog reads, cache files, or global catalog mutation.
    private static final SkinCatalog.Kit RIFLES = new SkinCatalog.Kit(920001, "fixture_loadout_rifles",
            "Fixture Loadout Rifles", 0, .1f, .5f, "common",
            Set.of("ak47", "glock", "m4a1"), Set.of("ak47", "glock", "m4a1"), false);
    private static final SkinCatalog.Kit SNIPER = new SkinCatalog.Kit(920002, "fixture_loadout_sniper",
            "Fixture Loadout Sniper", 0, .2f, .6f, "rare",
            Set.of("awp", "glock"), Set.of("awp", "glock"), false);
    private static final SkinCatalog.Kit OTHERS = new SkinCatalog.Kit(920003, "fixture_loadout_others",
            "Fixture Loadout Others", 0, 0, 1, "mythical",
            Set.of("m4a1", "deagle"), Set.of("m4a1", "deagle"), false);
    private static final SkinCatalog.Kit UNAVAILABLE = new SkinCatalog.Kit(920004, "fixture_loadout_unavailable",
            "Fixture Loadout Unavailable", 0, 0, 1, "common",
            Set.of("ak47", "awp"), Set.of(), false);
    private static final List<SkinCatalog.Kit> KITS = List.of(RIFLES, SNIPER, OTHERS, UNAVAILABLE);
    private static final ToLongFunction<String> UNIFORM = rarity -> 1L;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void itemsUseTheirPools(GameTestHelper helper) {
        helper.assertValueEqual(SkinGameplay.WEAPON_CASE.pool(), WeaponCaseItem.Pool.ALL, "weapon case pool");
        helper.assertValueEqual(SkinGameplay.INVENTORY_WEAPON_CASE.pool(), WeaponCaseItem.Pool.LOADOUT,
                "loadout case pool");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void rollsAndFillerOnlyUseCarriedGuns(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE));
            ItemStack skinnedAk = new ItemStack(CsmcMod.GUNS.get("ak47"));
            skinnedAk.set(SkinGameplay.SKIN, new SkinData(920001, .3f, 5));
            player.getInventory().setItem(3, skinnedAk);
            player.getInventory().setItem(20, new ItemStack(CsmcMod.GUNS.get("awp")));
            player.getInventory().setItem(30, new ItemStack(CsmcMod.GUNS.get("ak47")));

            List<String> weapons = WeaponCaseItem.Pool.LOADOUT.weapons(player);
            helper.assertValueEqual(Set.copyOf(weapons), Set.of("ak47", "awp"), "eligible guns");
            helper.assertValueEqual(weapons.size(), 2, "each gun counts once");

            Set<String> seen = new HashSet<>();
            // One seeded stream: first draws of nearby legacy seeds are strongly correlated.
            RandomSource random = RandomSource.create(0x4c4f4144L);
            for (long seed = 0; seed < 512; seed++) {
                WeaponCaseItem.Rolled rolled = WeaponCaseItem.rollResult(KITS, weapons, random, UNIFORM)
                        .orElseThrow();
                String weapon = rolled.pick().weapon();
                helper.assertTrue(weapon.equals("ak47") || weapon.equals("awp"), "rolled a carried gun: " + weapon);
                seen.add(weapon);
                if (seed % 32 == 0) {
                    CaseOpeningPayload reel = CaseOpeningPayload.create(WeaponCaseItem.Pool.LOADOUT, rolled, KITS,
                            weapons, seed * 7 + 1, UNIFORM);
                    helper.assertValueEqual(reel.pool(), WeaponCaseItem.Pool.LOADOUT, "reel names the loadout case");
                    for (CaseOpeningPayload.Entry entry : reel.strip()) {
                        helper.assertTrue(entry.weapon().equals("ak47") || entry.weapon().equals("awp"),
                                "filler only shows carried guns: " + entry);
                    }
                }
            }
            helper.assertValueEqual(seen, Set.of("ak47", "awp"), "both carried guns are reachable");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void offhandAndArmorGunsCount(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(CsmcMod.GUNS.get("glock")));
            helper.assertValueEqual(WeaponCaseItem.Pool.LOADOUT.weapons(player), List.of("glock"), "offhand gun counts");
            player.getInventory().armor.set(0, new ItemStack(CsmcMod.GUNS.get("m4a1")));
            helper.assertValueEqual(Set.copyOf(WeaponCaseItem.Pool.LOADOUT.weapons(player)), Set.of("glock", "m4a1"),
                    "armor slot gun counts");

            player.getInventory().armor.set(0, ItemStack.EMPTY);
            InteractionResultHolder<ItemStack> opened = open(player, 11);
            helper.assertValueEqual(opened.getResult(), InteractionResult.CONSUME, "offhand gun opens the case");
            helper.assertValueEqual(opened.getObject().get(SkinGameplay.SKIN_WEAPON), "glock", "offhand gun skin");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void noGunsKeepsTheCase(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE));
            helper.assertTrue(WeaponCaseItem.Pool.LOADOUT.weapons(player).isEmpty(), "the case is not a gun");
            InteractionResultHolder<ItemStack> opened = open(player, 12);
            helper.assertValueEqual(opened.getResult(), InteractionResult.FAIL, "no guns fails");
            helper.assertValueEqual(countOf(player, SkinGameplay.INVENTORY_WEAPON_CASE), 1, "no guns keeps the case");
            helper.assertValueEqual(attachments(player).size(), 0, "no guns grants nothing");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void gunsWithoutAvailableKitsKeepTheCase(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE));
            player.getInventory().setItem(4, new ItemStack(CsmcMod.GUNS.get("ak47")));
            InteractionResultHolder<ItemStack> opened = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND,
                    WeaponCaseItem.Pool.LOADOUT, List.of(UNAVAILABLE, OTHERS), RandomSource.create(13), UNIFORM);
            helper.assertValueEqual(opened.getResult(), InteractionResult.FAIL, "unavailable kits fail");
            helper.assertValueEqual(countOf(player, SkinGameplay.INVENTORY_WEAPON_CASE), 1, "unavailable kits keep the case");
            helper.assertValueEqual(attachments(player).size(), 0, "unavailable kits grant nothing");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void openingGrantsOneAndConsumesOne(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE, 3));
            player.getInventory().setItem(5, new ItemStack(CsmcMod.GUNS.get("awp")));
            InteractionResultHolder<ItemStack> stacked = open(player, 14);
            helper.assertValueEqual(stacked.getResult(), InteractionResult.CONSUME, "stacked case opens");
            player.setItemInHand(InteractionHand.MAIN_HAND, stacked.getObject());
            helper.assertValueEqual(countOf(player, SkinGameplay.INVENTORY_WEAPON_CASE), 2, "exactly one case consumed");
            List<ItemStack> granted = attachments(player);
            helper.assertValueEqual(granted.size(), 1, "exactly one attachment granted");
            helper.assertValueEqual(granted.get(0).get(SkinGameplay.SKIN_WEAPON), "awp", "attachment for the carried gun");

            // The last case becomes the attachment in the same hand, like the Weapon Case.
            player.getInventory().clearContent();
            player.getInventory().setItem(5, new ItemStack(CsmcMod.GUNS.get("awp")));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE));
            InteractionResultHolder<ItemStack> last = open(player, 15);
            helper.assertValueEqual(last.getResult(), InteractionResult.CONSUME, "last case opens");
            player.setItemInHand(InteractionHand.MAIN_HAND, last.getObject());
            helper.assertValueEqual(countOf(player, SkinGameplay.INVENTORY_WEAPON_CASE), 0, "last case consumed");
            helper.assertValueEqual(attachments(player).size(), 1, "last case grants one attachment");
            helper.assertTrue(player.getMainHandItem().is(SkinGameplay.WEAPON_SKIN), "attachment replaces the last case");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void weaponCaseStaysUnrestricted(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            player.getInventory().setItem(4, new ItemStack(CsmcMod.GUNS.get("ak47")));
            helper.assertValueEqual(WeaponCaseItem.Pool.ALL.weapons(player), List.copyOf(CsmcMod.SKINNABLE.keySet()),
                    "weapon case pool is every gun and knife");
            Set<String> seen = new HashSet<>();
            RandomSource random = RandomSource.create(0x414c4cL);
            for (int draw = 0; draw < 64; draw++) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.WEAPON_CASE, 2));
                InteractionResultHolder<ItemStack> opened = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND,
                        SkinGameplay.WEAPON_CASE.pool(), KITS, random, UNIFORM);
                helper.assertValueEqual(opened.getResult(), InteractionResult.CONSUME, "weapon case opens");
                for (ItemStack stack : attachments(player)) seen.add(stack.get(SkinGameplay.SKIN_WEAPON));
                player.getInventory().clearContent();
                player.getInventory().setItem(4, new ItemStack(CsmcMod.GUNS.get("ak47")));
            }
            helper.assertTrue(seen.contains("deagle") && seen.contains("m4a1") && seen.contains("glock"),
                    "weapon case rolls guns the opener does not carry: " + seen);
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    private static InteractionResultHolder<ItemStack> open(ServerPlayer player, long seed) {
        return WeaponCaseItem.open(player, InteractionHand.MAIN_HAND, SkinGameplay.INVENTORY_WEAPON_CASE.pool(), KITS,
                RandomSource.create(seed), UNIFORM);
    }

    private static ServerPlayer survivalPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        return player;
    }

    private static int countOf(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static List<ItemStack> attachments(ServerPlayer player) {
        List<ItemStack> found = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(SkinGameplay.WEAPON_SKIN)) found.add(stack);
        }
        return found;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
