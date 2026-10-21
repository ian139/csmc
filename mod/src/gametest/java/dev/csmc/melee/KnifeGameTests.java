package dev.csmc.melee;

import com.mojang.authlib.GameProfile;
import dev.csmc.CsmcMod;
import dev.csmc.item.KnifeItem;
import dev.csmc.item.KevlarArmor;
import dev.csmc.network.GunActionPayload;
import dev.csmc.network.KnifeActionPayload;
import dev.csmc.network.KnifeEventPayload;
import dev.csmc.server.GunSystem;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.gameplay.SkinCraftingRecipe;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.SkinPicker;
import dev.csmc.skin.gameplay.WeaponCaseItem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

/** Server knife melee, knife stats, and knives in the skin system. */
public final class KnifeGameTests implements FabricGameTest {
    private static final double EPS = 1.0e-4;
    private static final ToLongFunction<String> UNIFORM = rarity -> 1L;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void statsLoadForEveryKnifeAndDiffer(GameTestHelper helper) {
        helper.assertValueEqual(CsmcMod.KNIFE_IDS.size(), 22, "knife ids");
        helper.assertValueEqual(List.copyOf(CsmcMod.KNIVES.keySet()), CsmcMod.KNIFE_IDS, "registered knives in order");
        helper.assertValueEqual(List.copyOf(KnifeStats.all().keySet()), CsmcMod.KNIFE_IDS, "stats for every knife");
        for (String id : CsmcMod.KNIFE_IDS) {
            KnifeItem knife = CsmcMod.KNIVES.get(id);
            helper.assertValueEqual(knife.weaponId(), CsmcMod.id(id), "knife id " + id);
            helper.assertTrue(CsmcMod.SKINNABLE.get(id) == knife, "skinnable " + id);
        }
        List<String> skinnable = new ArrayList<>(CsmcMod.GUNS.keySet());
        skinnable.addAll(CsmcMod.KNIFE_IDS);
        skinnable.addAll(CsmcMod.GLOVE_IDS);
        helper.assertValueEqual(List.copyOf(CsmcMod.SKINNABLE.keySet()), skinnable, "guns, knives, then gloves");
        // Only the CT and T defaults share a profile; every special knife plays differently.
        helper.assertValueEqual(new HashSet<>(KnifeStats.all().values()).size(), 21, "distinct stat profiles");
        helper.assertValueEqual(KnifeStats.get("knife"), KnifeStats.get("knife_t"), "default knives match");
        KnifeStats kukri = KnifeStats.get("knife_kukri");
        KnifeStats karambit = KnifeStats.get("knife_karambit");
        KnifeStats base = KnifeStats.get("knife");
        helper.assertTrue(kukri.slashDamage() > base.slashDamage() && base.slashDamage() > karambit.slashDamage(), "damage tiers");
        helper.assertTrue(kukri.slashRange() > base.slashRange() && base.slashRange() > karambit.slashRange(), "range tiers");
        helper.assertTrue(kukri.slashCooldown() > base.slashCooldown() && base.slashCooldown() > karambit.slashCooldown(),
                "recharge tiers");
        helper.assertTrue(Math.abs(base.slashDamage() - 40) < EPS && Math.abs(base.stabDamage() - 65) < EPS
                && Math.abs(base.slashBackstabDamage() - 90) < EPS && Math.abs(base.stabBackstabDamage() - 180) < EPS
                && Math.abs(base.slashRange() - 48) < EPS && Math.abs(base.stabRange() - 32) < EPS, "CS:GO baseline");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void slashFollowupAndStabDamage(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        Husk husk = husk(helper, new Vec3(1.5, 1.0, 3.0), 180.0F);
        MeleeSystem.Attack first = slash(player);
        helper.assertValueEqual(first.outcome(), MeleeSystem.Outcome.HIT, "first slash hits");
        helper.assertTrue(first.target() == husk && first.hurt() && !first.backstab() && !first.followup(), "first slash");
        helper.assertTrue(Math.abs(first.csDamage() - 40) < EPS && Math.abs(first.health() - 8.0F) < EPS,
                "40 CS = 8 health: " + first);
        helper.assertValueEqual(first.events(), List.of(KnifeEventPayload.SLASH, KnifeEventPayload.HIT), "slash events");
        helper.assertTrue(Math.abs(husk.getHealth() - 192.0F) < EPS, "husk took 8: " + husk.getHealth());
        helper.runAfterDelay(11, () -> {
            MeleeSystem.Attack followup = slash(player);
            helper.assertTrue(followup.outcome() == MeleeSystem.Outcome.HIT && followup.followup(), "follow-up " + followup);
            helper.assertTrue(Math.abs(followup.csDamage() - 25) < EPS, "follow-up deals 25: " + followup);
            helper.runAfterDelay(9, () -> {
                MeleeSystem.Attack stab = stab(player);
                helper.assertTrue(stab.outcome() == MeleeSystem.Outcome.HIT && !stab.followup(), "stab " + stab);
                helper.assertTrue(Math.abs(stab.csDamage() - 65) < EPS, "stab deals 65: " + stab);
                helper.assertValueEqual(stab.events(), List.of(KnifeEventPayload.STAB, KnifeEventPayload.HIT), "stab events");
                helper.assertTrue(Math.abs(husk.getHealth() - (200.0F - 8.0F - 5.0F - 13.0F)) < 1.0e-3,
                        "husk health " + husk.getHealth());
                helper.assertValueEqual(husk.getLastDamageSource().getMsgId(), "csmc.knife", "knife damage type");
                helper.assertTrue(husk.getLastDamageSource().getEntity() == player, "attributed to attacker");
                remove(player);
                helper.succeed();
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void followupWindowExpires(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        husk(helper, new Vec3(1.5, 1.0, 3.0), 180.0F);
        slash(player);
        // Ready after 10 ticks; the follow-up window lasts 20 more.
        helper.runAfterDelay(35, () -> {
            MeleeSystem.Attack late = slash(player);
            helper.assertTrue(late.outcome() == MeleeSystem.Outcome.HIT && !late.followup(), "window expired " + late);
            helper.assertTrue(Math.abs(late.csDamage() - 40) < EPS, "full slash again");
            remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void backstabMultiplier(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        // Facing +Z, away from the attacker at its back.
        Husk husk = husk(helper, new Vec3(1.5, 1.0, 3.0), 0.0F);
        helper.assertTrue(MeleeSystem.isBackstab(player, husk), "attacker is behind");
        MeleeSystem.Attack slash = slash(player);
        helper.assertTrue(slash.backstab() && Math.abs(slash.csDamage() - 90) < EPS, "slash backstab 90: " + slash);
        helper.assertValueEqual(slash.events(), List.of(KnifeEventPayload.SLASH_BACKSTAB, KnifeEventPayload.HIT), "slash backstab events");
        MeleeSystem.reset(player);
        MeleeSystem.Attack stab = stab(player);
        helper.assertTrue(stab.backstab() && Math.abs(stab.csDamage() - 180) < EPS, "stab backstab 180: " + stab);
        helper.assertValueEqual(stab.events(), List.of(KnifeEventPayload.BACKSTAB, KnifeEventPayload.HIT), "stab backstab events");
        Husk side = husk(helper, new Vec3(3.5, 1.0, 3.0), 90.0F);
        helper.assertTrue(!MeleeSystem.isBackstab(player, side), "side-on is not a backstab");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void rangeLimitsPerAttackAndKnife(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        // Face 2.35 blocks from the eye with the hull: inside the 3-block slash, outside the 2-block stab.
        husk(helper, new Vec3(1.5, 1.0, 4.4), 180.0F);
        helper.assertValueEqual(stab(player).outcome(), MeleeSystem.Outcome.MISS, "stab is too short");
        MeleeSystem.reset(player);
        helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.HIT, "slash reaches");
        MeleeSystem.reset(player);
        player.getInventory().setItem(0, new ItemStack(CsmcMod.KNIVES.get("knife_push")));
        // Shadow Daggers slash 38 u = 2.375 blocks: just reaches 2.35.
        helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.HIT, "daggers just reach");
        remove(player);
        ServerPlayer far = attacker(helper, "knife_kukri");
        far.moveTo(far.getX(), far.getY(), far.getZ() - 1.5, 0.0F, 0.0F);
        far.setYHeadRot(0.0F);
        // 3.85 blocks: beyond even the Kukri's 58 u = 3.625 blocks.
        helper.assertValueEqual(slash(far).outcome(), MeleeSystem.Outcome.MISS, "out of range");
        remove(far);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void cooldownRefusesEarlyAttacks(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        Husk husk = husk(helper, new Vec3(1.5, 1.0, 3.0), 180.0F);
        helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.HIT, "first slash");
        float health = husk.getHealth();
        MeleeSystem.Attack early = slash(player);
        helper.assertValueEqual(early.outcome(), MeleeSystem.Outcome.REFUSED, "second slash refused");
        helper.assertTrue(early.events().isEmpty(), "refused attacks broadcast nothing");
        helper.assertValueEqual(stab(player).outcome(), MeleeSystem.Outcome.REFUSED, "stab shares the cooldown");
        helper.assertValueEqual(MeleeSystem.handle(player, (byte) 7).outcome(), MeleeSystem.Outcome.REFUSED, "unknown action");
        helper.assertTrue(husk.getHealth() == health, "no damage while cooling down");
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.REFUSED, "still cooling down at 5 ticks");
            helper.runAfterDelay(6, () -> {
                helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.HIT, "ready after 0.5 s");
                remove(player);
                helper.succeed();
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void wallHitNeverBreaksBlocks(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        BlockPos wall = new BlockPos(1, 2, 2);
        helper.setBlock(wall, Blocks.GLASS);
        MeleeSystem.Attack attack = slash(player);
        helper.assertValueEqual(attack.outcome(), MeleeSystem.Outcome.HITWALL, "slash hits the wall");
        helper.assertValueEqual(attack.events(), List.of(KnifeEventPayload.SLASH, KnifeEventPayload.HITWALL), "events");
        helper.assertBlockPresent(Blocks.GLASS, wall);
        // A mob right behind the wall, within stab reach (1.35 blocks with the hull), is shielded.
        Husk shielded = husk(helper, new Vec3(1.5, 1.0, 3.4), 180.0F);
        MeleeSystem.reset(player);
        helper.assertValueEqual(stab(player).outcome(), MeleeSystem.Outcome.HITWALL, "stab stops at the wall");
        helper.assertTrue(shielded.getHealth() == 200.0F, "shielded mob untouched");
        helper.assertBlockPresent(Blocks.GLASS, wall);
        helper.setBlock(wall, Blocks.AIR);
        MeleeSystem.reset(player);
        helper.assertValueEqual(stab(player).outcome(), MeleeSystem.Outcome.HIT, "the same mob is in stab reach");
        helper.setBlock(wall, Blocks.GLASS);
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(wall);
        KnifeItem knife = (KnifeItem) player.getMainHandItem().getItem();
        helper.assertTrue(!knife.canAttackBlock(level.getBlockState(absolute), level, absolute, player), "never mines");
        helper.assertValueEqual(AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                absolute, Direction.NORTH), InteractionResult.FAIL, "vanilla mining cancelled");
        Husk husk = husk(helper, new Vec3(3.5, 1.0, 3.5), 0.0F);
        helper.assertValueEqual(AttackEntityCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                husk, null), InteractionResult.FAIL, "vanilla attack cancelled");
        player.setGameMode(GameType.CREATIVE);
        helper.assertTrue(!player.gameMode.destroyBlock(absolute), "creative cannot break with a knife");
        helper.assertBlockPresent(Blocks.GLASS, wall);
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void thinPaneShieldsTargetPressedBehindIt(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        // An east-west pane row: collision is 2 px thick at z = 3.4375..3.5625.
        var pane = Blocks.GLASS_PANE.defaultBlockState()
                .setValue(CrossCollisionBlock.EAST, true).setValue(CrossCollisionBlock.WEST, true);
        for (int x = 0; x < 3; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), pane);
            helper.setBlock(new BlockPos(x, 2, 3), pane);
        }
        // Box front at 3.6: the hull-inflated front (3.35) pokes through the pane.
        Husk husk = husk(helper, new Vec3(1.5, 1.0, 3.9), 180.0F);
        MeleeSystem.Attack attack = slash(player);
        helper.assertValueEqual(attack.outcome(), MeleeSystem.Outcome.HITWALL, "pane blocks the slash: " + attack);
        helper.assertTrue(husk.getHealth() == 200.0F, "no damage through the pane");
        for (int x = 0; x < 3; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 2, 3), Blocks.AIR);
        }
        MeleeSystem.reset(player);
        helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.HIT, "in reach without the pane");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void elevatedTargetOverLedgeIsHitAlongTheAimRay(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        // Looking 15 degrees up: the aim ray clears the ledge top (y 3.022 at z 3) and meets the husk's real box,
        // while the hull-contact point projected onto the box would dip into the ledge (y 2.974).
        player.setXRot(-15.0F);
        helper.setBlock(new BlockPos(1, 2, 3), Blocks.STONE);
        Husk husk = husk(helper, new Vec3(1.5, 3.0, 3.9), 180.0F);
        MeleeSystem.Attack attack = slash(player);
        helper.assertValueEqual(attack.outcome(), MeleeSystem.Outcome.HIT, "aim ray reaches the husk: " + attack);
        helper.assertTrue(attack.target() == husk && husk.getHealth() < 200.0F, "husk damaged");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void hitsKnockBackModestly(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        Husk husk = husk(helper, new Vec3(1.5, 1.0, 3.0), 180.0F);
        husk.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0.0);
        husk.setDeltaMovement(Vec3.ZERO);
        helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.HIT, "hit");
        Vec3 push = husk.getDeltaMovement();
        double horizontal = Math.sqrt(push.x * push.x + push.z * push.z);
        helper.assertTrue(push.z > 0.0 && Math.abs(horizontal - MeleeSystem.KNOCKBACK) < 1.0e-3,
                "pushed away by " + MeleeSystem.KNOCKBACK + ": " + push);
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void vanillaUseIsCancelledWithKnife(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        ServerLevel level = helper.getLevel();
        BlockPos door = helper.absolutePos(new BlockPos(1, 1, 3));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(door), Direction.NORTH, door, false);
        for (InteractionHand hand : InteractionHand.values()) {
            helper.assertValueEqual(UseItemCallback.EVENT.invoker().interact(player, level, hand).getResult(),
                    InteractionResult.FAIL, "item use cancelled: " + hand);
            helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, level, hand, hit),
                    InteractionResult.FAIL, "block use cancelled: " + hand);
        }
        player.getInventory().setItem(0, new ItemStack(Items.STICK));
        helper.assertValueEqual(UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit),
                InteractionResult.PASS, "other items keep vanilla use");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void aimUsesCurrentRotationNotStaleHead(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        // Head still faces -Z (as before a look packet is processed); the current rotation faces the mob at +Z.
        place(helper, player, new Vec3(1.5, 1.0, 1.5), 180.0F);
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        helper.assertTrue(player.getYHeadRot() == 180.0F, "head rotation left stale");
        husk(helper, new Vec3(1.5, 1.0, 3.0), 180.0F);
        helper.assertValueEqual(slash(player).outcome(), MeleeSystem.Outcome.HIT, "traced along the current yaw");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void backstabUsesTargetPlayersCurrentYaw(GameTestHelper helper) {
        ServerPlayer attacker = player(helper, "kn_bsa", new Vec3(1.5, 1.0, 1.5), 0.0F);
        ServerPlayer target = player(helper, "kn_bsb", new Vec3(1.5, 1.0, 3.0), 180.0F);
        try {
            // Target just turned away (+Z); its head rotation still faces the attacker.
            target.setYRot(0.0F);
            helper.assertTrue(target.getYHeadRot() == 180.0F, "head rotation left stale");
            helper.assertTrue(MeleeSystem.isBackstab(attacker, target), "current yaw faces away");
            target.setYRot(180.0F);
            target.setYHeadRot(0.0F);
            helper.assertTrue(!MeleeSystem.isBackstab(attacker, target), "current yaw faces the attacker");
        } finally {
            remove(attacker);
            remove(target);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_knife_pvp_off")
    public void pvpOffSparesPlayers(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        boolean pvp = server.isPvpAllowed();
        ServerPlayer attacker = player(helper, "kn_pvpa", new Vec3(1.5, 1.0, 1.5), 0.0F);
        ServerPlayer target = player(helper, "kn_pvpb", new Vec3(1.5, 1.0, 3.0), 180.0F);
        try {
            server.setPvpAllowed(false);
            attacker.getInventory().setItem(attacker.getInventory().selected, new ItemStack(CsmcMod.KNIVES.get("knife")));
            float health = target.getHealth();
            MeleeSystem.Attack attack = slash(attacker);
            helper.assertTrue(attack.outcome() == MeleeSystem.Outcome.HIT && attack.target() == target, "traced " + attack);
            helper.assertTrue(!attack.hurt() && attack.health() == 0.0F && target.getHealth() == health, "PvP off guard");
        } finally {
            server.setPvpAllowed(pvp);
            remove(attacker);
            remove(target);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_knife_pvp_team")
    public void teamGuardSparesTeammates(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        boolean pvp = server.isPvpAllowed();
        ServerPlayer attacker = player(helper, "kn_teama", new Vec3(1.5, 1.0, 1.5), 0.0F);
        ServerPlayer mate = player(helper, "kn_teamb", new Vec3(1.5, 1.0, 3.0), 180.0F);
        PlayerTeam team = server.getScoreboard().addPlayerTeam("kn_knife_team");
        try {
            server.setPvpAllowed(true);
            team.setAllowFriendlyFire(false);
            server.getScoreboard().addPlayerToTeam(attacker.getScoreboardName(), team);
            server.getScoreboard().addPlayerToTeam(mate.getScoreboardName(), team);
            attacker.getInventory().setItem(attacker.getInventory().selected, new ItemStack(CsmcMod.KNIVES.get("knife")));
            MeleeSystem.Attack attack = stab(attacker);
            helper.assertTrue(attack.target() == mate && !attack.hurt() && attack.health() == 0.0F, "team guard " + attack);
        } finally {
            server.getScoreboard().removePlayerTeam(team);
            server.setPvpAllowed(pvp);
            remove(attacker);
            remove(mate);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void armorRatioReducesKnifeDamage(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        Husk husk = husk(helper, new Vec3(1.5, 1.0, 3.0), 180.0F);
        husk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        MeleeSystem.Attack attack = slash(player);
        // Iron gets half kevlar's reduction: 40 * (1 - .5 * (1 - 1.7 * .5)) = 37 CS.
        helper.assertTrue(Math.abs(attack.csDamage() - 37) < EPS, "iron slash " + attack);
        helper.assertValueEqual(husk.getItemBySlot(EquipmentSlot.CHEST).getDamageValue(), 1, "iron knife wear");
        MeleeSystem.reset(player);
        husk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(KevlarArmor.VEST));
        MeleeSystem.Attack kevlar = slash(player);
        helper.assertTrue(Math.abs(kevlar.csDamage() - 34) < EPS, "kevlar slash " + kevlar);
        helper.assertValueEqual(husk.getItemBySlot(EquipmentSlot.CHEST).getDamageValue(), 3, "kevlar knife wear");
        MeleeSystem.reset(player);
        husk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.STONE));
        helper.assertTrue(Math.abs(slash(player).csDamage() - 40) < EPS, "nonarmor knife hit");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void knifeDeathMessageKey(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife");
        helper.assertValueEqual(MeleeSystem.source(helper.getLevel(), player).getMsgId(), "csmc.knife", "death.attack.csmc.knife");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void inspectAcceptsKnives(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife_karambit");
        GunSystem.handle(player, GunActionPayload.INSPECT);
        helper.assertValueEqual(GunSystem.inspecting(player), CsmcMod.id("knife_karambit"), "knife inspect accepted");
        husk(helper, new Vec3(1.5, 1.0, 3.0), 180.0F);
        slash(player);
        helper.assertTrue(GunSystem.inspecting(player) == null, "an attack ends the inspect");
        player.getInventory().setItem(0, new ItemStack(Items.STICK));
        GunSystem.handle(player, GunActionPayload.INSPECT);
        helper.assertTrue(GunSystem.inspecting(player) == null, "non-weapons cannot inspect");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void knifeSkinCraftAppliesAndRemoves(GameTestHelper helper) {
        SkinCraftingRecipe recipe = new SkinCraftingRecipe(CraftingBookCategory.MISC);
        SkinData fade = new SkinData(38, 0.05F, 412);
        ItemStack karambit = new ItemStack(CsmcMod.KNIVES.get("knife_karambit"));
        CraftingInput apply = grid(karambit, SkinGameplay.skin("knife_karambit", fade));
        helper.assertTrue(recipe.matches(apply, helper.getLevel()), "knife + its skin matches");
        ItemStack skinned = recipe.assemble(apply, helper.getLevel().registryAccess());
        helper.assertValueEqual(skinned.getItem(), CsmcMod.KNIVES.get("knife_karambit"), "still a karambit");
        helper.assertValueEqual(skinned.get(SkinGameplay.SKIN), fade, "skin applied");
        helper.assertValueEqual(skinned.get(SkinGameplay.SKIN_WEAPON), "knife_karambit", "skin weapon tag");
        helper.assertTrue(!recipe.matches(grid(karambit, SkinGameplay.skin("knife_flip", fade)), helper.getLevel()),
                "another knife's skin is rejected");
        helper.assertTrue(!recipe.matches(grid(karambit, SkinGameplay.skin("ak47", fade)), helper.getLevel()),
                "a gun skin is rejected");
        CraftingInput remove = grid(skinned);
        helper.assertTrue(recipe.matches(remove, helper.getLevel()), "lone skinned knife matches");
        ItemStack plain = recipe.assemble(remove, helper.getLevel().registryAccess());
        helper.assertTrue(!plain.has(SkinGameplay.SKIN) && !plain.has(SkinGameplay.SKIN_WEAPON), "skin removed");
        ItemStack returned = recipe.getRemainingItems(remove).get(0);
        helper.assertTrue(returned.is(SkinGameplay.WEAPON_SKIN) && fade.equals(returned.get(SkinGameplay.SKIN))
                && "knife_karambit".equals(returned.get(SkinGameplay.SKIN_WEAPON)), "attachment returned");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void pickerAppliesToHeldKnife(GameTestHelper helper) {
        ServerPlayer player = attacker(helper, "knife_butterfly");
        SkinCatalog.Kit doppler = kit(930001, "common", Set.of("knife_butterfly"), Set.of("knife_butterfly"));
        SkinCatalog.Kit gunOnly = kit(930002, "common", Set.of("ak47"), Set.of("ak47"));
        List<SkinCatalog.Kit> kits = List.of(doppler, gunOnly);
        helper.assertValueEqual(SkinPicker.apply(player, true, kits, 930001, 0.1F, 7), SkinPicker.Outcome.APPLIED, "applied");
        ItemStack held = player.getMainHandItem();
        helper.assertValueEqual(held.get(SkinGameplay.SKIN), new SkinData(930001, 0.1F, 7), "skin on knife");
        helper.assertValueEqual(held.get(SkinGameplay.SKIN_WEAPON), "knife_butterfly", "weapon tag");
        helper.assertValueEqual(SkinPicker.apply(player, true, kits, 930002, 0.1F, 7), SkinPicker.Outcome.UNSUPPORTED,
                "gun-only kit rejected");
        helper.assertValueEqual(SkinPicker.apply(player, true, kits, 0, 0F, 0), SkinPicker.Outcome.REMOVED, "removed");
        helper.assertTrue(!player.getMainHandItem().has(SkinGameplay.SKIN), "cleared");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void weaponCaseYieldsKnivesOnlyAtGoldTier(GameTestHelper helper) {
        // A common kit shared by a gun and a knife: the knife pair still lands in the gold tier.
        SkinCatalog.Kit shared = kit(930010, "common", Set.of("ak47", "knife_karambit"), Set.of("ak47", "knife_karambit"));
        SkinCatalog.Kit gun = kit(930011, "rare", Set.of("glock"), Set.of("glock"));
        List<SkinCatalog.Kit> kits = List.of(shared, gun);
        helper.assertValueEqual(WeaponCaseItem.pairTier(shared, "knife_karambit"), SkinCatalog.RARITY_TIERS.size() - 1,
                "knife pair tier");
        helper.assertValueEqual(WeaponCaseItem.pairTier(shared, "ak47"), 0, "gun pair keeps kit rarity");
        ServerPlayer player = attacker(helper, "knife");
        List<String> all = WeaponCaseItem.Pool.ALL.weapons(player);
        helper.assertTrue(all.containsAll(CsmcMod.KNIFE_IDS) && all.containsAll(CsmcMod.GUNS.keySet()), "ALL pool");
        remove(player);
        RandomSource random = RandomSource.create(0x4b4e4946L);
        boolean knife = false;
        for (int i = 0; i < 300; i++) {
            WeaponCaseItem.Pick pick = WeaponCaseItem.pick(kits, all, random, UNIFORM).orElseThrow();
            if (pick.weapon().equals("knife_karambit")) {
                knife = true;
                helper.assertValueEqual(pick.tier(), SkinCatalog.RARITY_TIERS.size() - 1, "knife at gold tier");
            } else {
                helper.assertTrue(pick.tier() != SkinCatalog.RARITY_TIERS.size() - 1, "guns stay in their tiers");
            }
        }
        helper.assertTrue(knife, "the ALL pool can yield a knife pair");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void loadoutCaseWithOnlyKarambitRollsKarambitSkins(GameTestHelper helper) {
        SkinCatalog.Kit shared = kit(930020, "common", Set.of("ak47", "knife_karambit", "knife_flip"),
                Set.of("ak47", "knife_karambit", "knife_flip"));
        List<SkinCatalog.Kit> kits = List.of(shared);
        ServerPlayer player = attacker(helper, "knife_karambit");
        helper.assertValueEqual(WeaponCaseItem.Pool.LOADOUT.weapons(player), List.of("knife_karambit"), "carried");
        player.getInventory().setItem(1, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE, 3));
        player.getInventory().selected = 1;
        for (long seed = 0; seed < 3; seed++) {
            var result = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND, WeaponCaseItem.Pool.LOADOUT, kits,
                    RandomSource.create(seed), UNIFORM);
            helper.assertTrue(result.getResult().consumesAction(), "opened");
            // The last case is replaced in hand by its attachment, as the item use would install it.
            player.setItemInHand(InteractionHand.MAIN_HAND, result.getObject());
        }
        int skins = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(SkinGameplay.WEAPON_SKIN)) continue;
            skins++;
            helper.assertValueEqual(stack.get(SkinGameplay.SKIN_WEAPON), "knife_karambit", "karambit skin only");
        }
        helper.assertValueEqual(skins, 3, "one attachment per case");
        int cases = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(SkinGameplay.INVENTORY_WEAPON_CASE)) {
                cases += player.getInventory().getItem(slot).getCount();
            }
        }
        helper.assertValueEqual(cases, 0, "every case consumed");
        remove(player);
        helper.succeed();
    }

    private static MeleeSystem.Attack slash(ServerPlayer player) {
        return MeleeSystem.handle(player, KnifeActionPayload.SLASH);
    }

    private static MeleeSystem.Attack stab(ServerPlayer player) {
        return MeleeSystem.handle(player, KnifeActionPayload.STAB);
    }

    private static SkinCatalog.Kit kit(int id, String rarity, Set<String> weapons, Set<String> available) {
        return new SkinCatalog.Kit(id, "fixture_knife_" + id, "Fixture Knife " + id, 0, 0F, 1F, rarity,
                weapons, available, false);
    }

    private static CraftingInput grid(ItemStack... occupied) {
        List<ItemStack> grid = new ArrayList<>(Collections.nCopies(4, ItemStack.EMPTY));
        for (int i = 0; i < occupied.length; i++) grid.set(i == 0 ? 0 : 3, occupied[i]);
        return CraftingInput.of(2, 2, grid);
    }

    /** A mock attacker at (1.5, 1, 1.5) relative, facing +Z, holding {@code knife} in hotbar slot 0. */
    private static ServerPlayer attacker(GameTestHelper helper, String knife) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(CsmcMod.KNIVES.get(knife)));
        place(helper, player, new Vec3(1.5, 1.0, 1.5), 0.0F);
        player.setNoGravity(true);
        MeleeSystem.reset(player);
        return player;
    }

    private static void place(GameTestHelper helper, ServerPlayer player, Vec3 relative, float yaw) {
        Vec3 at = helper.absoluteVec(relative);
        player.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
    }

    private static ServerPlayer player(GameTestHelper helper, String name, Vec3 relative, float yaw) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        Vec3 at = helper.absoluteVec(relative);
        player.teleportTo(level, at.x, at.y, at.z, yaw, 0.0F);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setNoGravity(true);
        MeleeSystem.reset(player);
        return player;
    }

    private static Husk husk(GameTestHelper helper, Vec3 relative, float yaw) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, relative);
        husk.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        husk.setHealth(200.0F);
        // Knife hits knock back; pin fixtures so later swings measure the same geometry.
        husk.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        husk.setNoGravity(true);
        Vec3 at = helper.absoluteVec(relative);
        husk.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        husk.setYHeadRot(yaw);
        husk.setYBodyRot(yaw);
        return husk;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
