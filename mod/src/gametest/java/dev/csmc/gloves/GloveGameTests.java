package dev.csmc.gloves;

import com.mojang.authlib.GameProfile;
import dev.csmc.CsmcMod;
import dev.csmc.item.GloveItem;
import dev.csmc.network.GloveSyncPayload;
import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.gameplay.SkinCraftingRecipe;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.SkinPicker;
import dev.csmc.skin.gameplay.WeaponCaseItem;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Glove items, the glove slot (menu, storage, death and respawn, sync) and gloves in the skin system. */
public final class GloveGameTests implements FabricGameTest {
    private static final ToLongFunction<String> UNIFORM = rarity -> 1L;
    private static final int GOLD = SkinCatalog.RARITY_TIERS.size() - 1;
    private static final int HOTBAR_0 = 36;
    private static final int OFFHAND = 45;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void glovesRegisteredAndSkinnable(GameTestHelper helper) {
        helper.assertValueEqual(CsmcMod.GLOVE_IDS, List.of("studded_bloodhound_gloves", "sporty_gloves",
                "slick_gloves", "leather_handwraps", "motorcycle_gloves", "specialist_gloves", "studded_hydra_gloves",
                "studded_brokenfang_gloves"), "glove ids");
        helper.assertValueEqual(List.copyOf(CsmcMod.GLOVES.keySet()), CsmcMod.GLOVE_IDS, "registered gloves in order");
        for (String id : CsmcMod.GLOVE_IDS) {
            GloveItem glove = CsmcMod.GLOVES.get(id);
            helper.assertValueEqual(glove.weaponId(), CsmcMod.id(id), "glove id " + id);
            helper.assertTrue(BuiltInRegistries.ITEM.get(CsmcMod.id(id)) == glove, "item csmc:" + id);
            helper.assertTrue(CsmcMod.SKINNABLE.get(id) == glove, "skinnable " + id);
            helper.assertValueEqual(new ItemStack(glove).getMaxStackSize(), 1, "gloves do not stack");
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void slotAcceptsOnlyGloves(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        InventoryMenu menu = player.inventoryMenu;
        helper.assertValueEqual(menu.slots.size(), GloveSlot.MENU_INDEX + 1, "glove slot appended last");
        Slot slot = menu.getSlot(GloveSlot.MENU_INDEX);
        helper.assertTrue(slot instanceof GloveSlot.GloveMenuSlot, "glove slot type");
        helper.assertTrue(slot.x == GloveSlot.SLOT_X && slot.y == GloveSlot.SLOT_Y, "slot position");
        helper.assertTrue(slot.x == menu.getSlot(OFFHAND).x && slot.y == menu.getSlot(OFFHAND).y - 18,
                "directly above the offhand slot");
        for (GloveItem glove : CsmcMod.GLOVES.values()) {
            helper.assertTrue(slot.mayPlace(new ItemStack(glove)), "accepts " + glove);
        }
        helper.assertTrue(!slot.mayPlace(new ItemStack(CsmcMod.AK47)), "rejects guns");
        helper.assertTrue(!slot.mayPlace(new ItemStack(CsmcMod.KNIVES.get("knife"))), "rejects knives");
        helper.assertTrue(!slot.mayPlace(new ItemStack(Items.LEATHER)), "rejects other items");
        helper.assertTrue(!slot.mayPlace(new ItemStack(Items.SHIELD)), "rejects shields");
        helper.assertValueEqual(slot.getMaxStackSize(new ItemStack(CsmcMod.GLOVES.get("sporty_gloves"))), 1, "max 1");

        // Placing by click: a non-glove stays on the cursor, a glove goes in.
        menu.setCarried(new ItemStack(Items.LEATHER, 5));
        menu.clicked(GloveSlot.MENU_INDEX, 0, ClickType.PICKUP, player);
        helper.assertTrue(GloveSlot.get(player).isEmpty() && menu.getCarried().getCount() == 5, "leather rejected");
        menu.setCarried(new ItemStack(CsmcMod.GLOVES.get("slick_gloves")));
        menu.clicked(GloveSlot.MENU_INDEX, 0, ClickType.PICKUP, player);
        helper.assertTrue(GloveSlot.get(player).is(CsmcMod.GLOVES.get("slick_gloves")) && menu.getCarried().isEmpty(),
                "glove placed by click");
        helper.assertTrue(slot.getItem() == GloveSlot.get(player), "slot is backed by the attachment");
        // Picking it up again empties the slot without a copy left behind.
        menu.clicked(GloveSlot.MENU_INDEX, 0, ClickType.PICKUP, player);
        helper.assertTrue(GloveSlot.get(player).isEmpty() && menu.getCarried().is(CsmcMod.GLOVES.get("slick_gloves")),
                "glove picked up");
        menu.setCarried(ItemStack.EMPTY);
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void shiftClickMovesGlovesBothWays(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        InventoryMenu menu = player.inventoryMenu;
        GloveItem sport = CsmcMod.GLOVES.get("sporty_gloves");
        GloveItem moto = CsmcMod.GLOVES.get("motorcycle_gloves");
        SkinData finish = new SkinData(10006, 0.2F, 42);
        ItemStack worn = new ItemStack(sport);
        worn.set(SkinGameplay.SKIN, finish);
        player.getInventory().setItem(0, worn);

        // Hotbar -> glove slot.
        menu.clicked(HOTBAR_0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(GloveSlot.get(player).is(sport) && finish.equals(GloveSlot.get(player).get(SkinGameplay.SKIN)),
                "glove shift-clicked in with its finish");
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "hotbar slot emptied");
        helper.assertValueEqual(gloves(player), 1, "no dupe in");

        // A second glove cannot replace the worn one: it moves hotbar -> main inventory as usual.
        player.getInventory().setItem(0, new ItemStack(moto));
        menu.clicked(HOTBAR_0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(GloveSlot.get(player).is(sport), "worn glove kept");
        helper.assertTrue(player.getInventory().getItem(0).isEmpty()
                && player.getInventory().countItem(moto) == 1, "second glove moved into main inventory");

        // Glove slot -> inventory.
        menu.clicked(GloveSlot.MENU_INDEX, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(GloveSlot.get(player).isEmpty(), "glove slot emptied");
        helper.assertValueEqual(player.getInventory().countItem(sport), 1, "glove back in the inventory");
        helper.assertValueEqual(gloves(player), 2, "no dupe out");
        boolean finished = false;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(sport)) finished = finish.equals(stack.get(SkinGameplay.SKIN));
        }
        helper.assertTrue(finished, "finish kept on the way out");

        // Offhand -> glove slot.
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(moto));
        menu.clicked(OFFHAND, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(GloveSlot.get(player).is(moto) && player.getOffhandItem().isEmpty(), "offhand -> glove slot");

        // A full inventory keeps the glove in its slot.
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        helper.assertTrue(menu.quickMoveStack(player, GloveSlot.MENU_INDEX).isEmpty(), "nothing moved");
        helper.assertTrue(GloveSlot.get(player).is(moto), "glove kept when the inventory is full");

        // Ordinary items never shift into the glove slot.
        player.getInventory().clearContent();
        GloveSlot.set(player, ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.LEATHER, 3));
        menu.clicked(HOTBAR_0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(GloveSlot.get(player).isEmpty(), "leather never enters the glove slot");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void attachmentPersistsInPlayerData(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack glove = new ItemStack(CsmcMod.GLOVES.get("specialist_gloves"));
        glove.set(SkinGameplay.SKIN, new SkinData(10033, 0.07F, 512));
        GloveSlot.set(player, glove);
        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        ServerPlayer loaded = player(helper);
        helper.assertTrue(GloveSlot.get(loaded).isEmpty(), "fresh player wears nothing");
        loaded.load(saved);
        helper.assertTrue(ItemStack.matches(GloveSlot.get(loaded), glove), "glove and finish persisted: "
                + GloveSlot.get(loaded));
        GloveSlot.set(player, ItemStack.EMPTY);
        helper.assertTrue(!player.saveWithoutId(new CompoundTag()).toString().contains("specialist_gloves"),
                "an empty slot saves nothing");
        remove(player);
        remove(loaded);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void deathDropsGloveUnlessKeepInventory(GameTestHelper helper) {
        GameRules.BooleanValue keep = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean previous = keep.get();
        try {
            // keepInventory off: the glove drops like armour and the respawned player wears nothing.
            keep.set(false, helper.getLevel().getServer());
            ServerPlayer player = player(helper);
            ItemStack glove = new ItemStack(CsmcMod.GLOVES.get("studded_hydra_gloves"));
            glove.set(SkinGameplay.SKIN, new SkinData(10057, 0.3F, 9));
            GloveSlot.set(player, glove.copy());
            player.die(player.damageSources().generic());
            helper.assertTrue(GloveSlot.get(player).isEmpty(), "glove left the dead player");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(player.blockPosition()).inflate(4), drop -> drop.getItem().is(glove.getItem()));
            helper.assertValueEqual(drops.size(), 1, "one glove dropped");
            helper.assertTrue(ItemStack.matches(drops.get(0).getItem(), glove), "dropped glove keeps its finish");
            drops.forEach(Entity::discard);
            ServerPlayer respawned = respawn(player);
            helper.assertTrue(GloveSlot.get(respawned).isEmpty(), "respawned without the glove");
            remove(respawned);

            // keepInventory on: nothing drops and the glove comes back on respawn.
            keep.set(true, helper.getLevel().getServer());
            ServerPlayer keeper = player(helper);
            GloveSlot.set(keeper, glove.copy());
            keeper.die(keeper.damageSources().generic());
            helper.assertTrue(ItemStack.matches(GloveSlot.get(keeper), glove), "kept through death");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(keeper.blockPosition()).inflate(4), drop -> drop.getItem().is(glove.getItem())).isEmpty(),
                    "nothing dropped with keepInventory");
            ServerPlayer kept = respawn(keeper);
            helper.assertTrue(ItemStack.matches(GloveSlot.get(kept), glove), "respawned wearing the glove");
            helper.assertTrue(kept.inventoryMenu.getSlot(GloveSlot.MENU_INDEX).getItem() == GloveSlot.get(kept),
                    "the new menu slot shows the kept glove");
            remove(kept);
        } finally {
            keep.set(previous, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void syncPayloadCarriesEntityAndGlove(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        GloveSyncPayload empty = GloveSlot.payload(player);
        helper.assertValueEqual(empty.entityId(), player.getId(), "entity id");
        helper.assertTrue(empty.glove().isEmpty(), "no glove: default arms gloves");
        ItemStack glove = new ItemStack(CsmcMod.GLOVES.get("leather_handwraps"));
        SkinData finish = new SkinData(10010, 0.45F, 777);
        glove.set(SkinGameplay.SKIN, finish);
        glove.set(SkinGameplay.SKIN_WEAPON, "leather_handwraps");
        GloveSlot.set(player, glove);
        GloveSyncPayload payload = GloveSlot.payload(player);
        helper.assertValueEqual(payload.entityId(), player.getId(), "entity id");
        helper.assertTrue(ItemStack.matches(payload.glove(), glove), "glove with finish");
        helper.assertTrue(payload.glove() != GloveSlot.get(player), "payload holds a copy");
        helper.assertValueEqual(payload.type(), GloveSyncPayload.TYPE, "type");

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        GloveSyncPayload.STREAM_CODEC.encode(buf, payload);
        GloveSyncPayload decoded = GloveSyncPayload.STREAM_CODEC.decode(buf);
        helper.assertValueEqual(decoded.entityId(), player.getId(), "decoded entity id");
        helper.assertTrue(decoded.glove().is(glove.getItem()) && decoded.glove().getCount() == 1, "decoded glove");
        helper.assertValueEqual(decoded.glove().get(SkinGameplay.SKIN), finish, "decoded finish");
        helper.assertTrue(!decoded.glove().has(SkinGameplay.SKIN_WEAPON), "only the item and its finish travel");

        // Arbitrary components never travel: the wire size does not grow with them.
        ItemStack named = glove.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(30000)));
        GloveSyncPayload.STREAM_CODEC.encode(buf, new GloveSyncPayload(player.getId(), named));
        helper.assertTrue(buf.readableBytes() < 64, "bounded payload: " + buf.readableBytes() + " bytes");
        GloveSyncPayload stripped = GloveSyncPayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(!stripped.glove().has(DataComponents.CUSTOM_NAME)
                && finish.equals(stripped.glove().get(SkinGameplay.SKIN)), "name stripped, finish kept");
        GloveSyncPayload.STREAM_CODEC.encode(buf, empty);
        helper.assertTrue(GloveSyncPayload.STREAM_CODEC.decode(buf).glove().isEmpty(), "empty round-trips");
        helper.assertValueEqual(buf.readableBytes(), 0, "fully consumed");
        buf.release();
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void gloveFinishCraftAppliesAndRemoves(GameTestHelper helper) {
        SkinCraftingRecipe recipe = new SkinCraftingRecipe(CraftingBookCategory.MISC);
        SkinData finish = new SkinData(10006, 0.1F, 3);
        GloveItem sport = CsmcMod.GLOVES.get("sporty_gloves");
        ItemStack glove = new ItemStack(sport);
        CraftingInput apply = grid(glove, SkinGameplay.skin("sporty_gloves", finish));
        helper.assertTrue(recipe.matches(apply, helper.getLevel()), "glove + its finish matches");
        ItemStack skinned = recipe.assemble(apply, helper.getLevel().registryAccess());
        helper.assertTrue(skinned.is(sport), "still sport gloves");
        helper.assertValueEqual(skinned.get(SkinGameplay.SKIN), finish, "finish applied");
        helper.assertValueEqual(skinned.get(SkinGameplay.SKIN_WEAPON), "sporty_gloves", "finish weapon tag");
        helper.assertTrue(!recipe.matches(grid(glove, SkinGameplay.skin("motorcycle_gloves", finish)), helper.getLevel()),
                "another glove's finish is rejected");
        helper.assertTrue(!recipe.matches(grid(glove, SkinGameplay.skin("knife_karambit", finish)), helper.getLevel()),
                "a knife skin is rejected");
        CraftingInput remove = grid(skinned);
        helper.assertTrue(recipe.matches(remove, helper.getLevel()), "lone finished glove matches");
        helper.assertTrue(!recipe.assemble(remove, helper.getLevel().registryAccess()).has(SkinGameplay.SKIN), "removed");
        ItemStack returned = recipe.getRemainingItems(remove).get(0);
        helper.assertTrue(returned.is(SkinGameplay.WEAPON_SKIN) && finish.equals(returned.get(SkinGameplay.SKIN))
                && "sporty_gloves".equals(returned.get(SkinGameplay.SKIN_WEAPON)), "attachment returned");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void pickerAppliesToHeldGlove(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().setItem(0, new ItemStack(CsmcMod.GLOVES.get("slick_gloves")));
        SkinCatalog.Kit driver = kit(940001, "ancient", Set.of("slick_gloves"));
        SkinCatalog.Kit moto = kit(940002, "ancient", Set.of("motorcycle_gloves"));
        List<SkinCatalog.Kit> kits = List.of(driver, moto);
        helper.assertValueEqual(SkinPicker.apply(player, true, kits, 940001, 0.15F, 11), SkinPicker.Outcome.APPLIED,
                "applied");
        helper.assertValueEqual(player.getMainHandItem().get(SkinGameplay.SKIN), new SkinData(940001, 0.15F, 11),
                "finish on the glove");
        helper.assertValueEqual(player.getMainHandItem().get(SkinGameplay.SKIN_WEAPON), "slick_gloves", "weapon tag");
        helper.assertValueEqual(SkinPicker.apply(player, true, kits, 940002, 0.15F, 11), SkinPicker.Outcome.UNSUPPORTED,
                "another glove's finish rejected");
        helper.assertValueEqual(SkinPicker.apply(player, false, kits, 940001, 0.15F, 11),
                SkinPicker.Outcome.NOT_OPERATOR, "operators only");
        helper.assertValueEqual(SkinPicker.apply(player, true, kits, 0, 0F, 0), SkinPicker.Outcome.REMOVED, "removed");
        helper.assertTrue(!player.getMainHandItem().has(SkinGameplay.SKIN), "cleared");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void weaponCaseYieldsGlovesOnlyAtGoldTier(GameTestHelper helper) {
        SkinCatalog.Kit shared = kit(940010, "common", Set.of("ak47", "sporty_gloves"));
        SkinCatalog.Kit gun = kit(940011, "rare", Set.of("glock"));
        List<SkinCatalog.Kit> kits = List.of(shared, gun);
        helper.assertValueEqual(WeaponCaseItem.pairTier(shared, "sporty_gloves"), GOLD, "glove pair tier");
        helper.assertValueEqual(WeaponCaseItem.pairTier(shared, "ak47"), 0, "gun pair keeps kit rarity");
        ServerPlayer player = player(helper);
        List<String> all = WeaponCaseItem.Pool.ALL.weapons(player);
        helper.assertTrue(all.containsAll(CsmcMod.GLOVE_IDS) && all.containsAll(CsmcMod.KNIFE_IDS)
                && all.containsAll(CsmcMod.GUNS.keySet()), "ALL pool has guns, knives and gloves");
        remove(player);
        RandomSource random = RandomSource.create(0x474c4f56L);
        boolean glove = false;
        for (int i = 0; i < 300; i++) {
            WeaponCaseItem.Pick pick = WeaponCaseItem.pick(kits, all, random, UNIFORM).orElseThrow();
            if (pick.weapon().equals("sporty_gloves")) {
                glove = true;
                helper.assertValueEqual(pick.tier(), GOLD, "glove at gold tier");
            } else {
                helper.assertTrue(pick.tier() != GOLD, "guns stay in their tiers");
            }
        }
        helper.assertTrue(glove, "the Weapon Case can yield a glove pair");
        ItemStack rolled = WeaponCaseItem.rollResult(List.of(kit(940012, "common", Set.of("motorcycle_gloves"))),
                random, UNIFORM).orElseThrow().stack();
        helper.assertTrue(rolled.is(SkinGameplay.WEAPON_SKIN)
                && "motorcycle_gloves".equals(rolled.get(SkinGameplay.SKIN_WEAPON)), "glove finish attachment");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void loadoutCaseExcludesGloves(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().setItem(0, new ItemStack(CsmcMod.AK47));
        player.getInventory().setItem(1, new ItemStack(CsmcMod.GLOVES.get("sporty_gloves")));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(CsmcMod.GLOVES.get("slick_gloves")));
        GloveSlot.set(player, new ItemStack(CsmcMod.GLOVES.get("motorcycle_gloves")));
        helper.assertValueEqual(WeaponCaseItem.Pool.LOADOUT.weapons(player), List.of("ak47"), "weapons only");

        // Only gloves carried: the Loadout Case has no weapons and is kept.
        player.getInventory().setItem(0, ItemStack.EMPTY);
        helper.assertTrue(WeaponCaseItem.Pool.LOADOUT.weapons(player).isEmpty(), "gloves alone are not weapons");
        player.getInventory().setItem(2, new ItemStack(SkinGameplay.INVENTORY_WEAPON_CASE));
        player.getInventory().selected = 2;
        var kits = List.of(kit(940020, "common", Set.of("sporty_gloves", "slick_gloves", "motorcycle_gloves")));
        var result = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND, WeaponCaseItem.Pool.LOADOUT, kits,
                RandomSource.create(1), UNIFORM);
        helper.assertTrue(!result.getResult().consumesAction(), "not opened");
        helper.assertValueEqual(player.getInventory().getItem(2).getCount(), 1, "case kept");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void creativeSlotPacketEditsOnlyWithGloves(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        GloveItem sport = CsmcMod.GLOVES.get("sporty_gloves");
        ItemStack finished = new ItemStack(sport);
        finished.set(SkinGameplay.SKIN, new SkinData(10006, 0.2F, 5));

        // Survival players cannot use the creative packet.
        creative(player, finished.copy());
        helper.assertTrue(GloveSlot.get(player).isEmpty(), "survival creative packet ignored");

        player.setGameMode(GameType.CREATIVE);
        creative(player, finished.copy());
        helper.assertTrue(ItemStack.matches(GloveSlot.get(player), finished), "creative packet wears the glove");
        creative(player, new ItemStack(Items.LEATHER));
        helper.assertTrue(ItemStack.matches(GloveSlot.get(player), finished), "non-glove rejected");
        creative(player, new ItemStack(CsmcMod.KNIVES.get("knife")));
        helper.assertTrue(ItemStack.matches(GloveSlot.get(player), finished), "knife rejected");
        creative(player, new ItemStack(CsmcMod.GLOVES.get("slick_gloves"), 2));
        helper.assertTrue(ItemStack.matches(GloveSlot.get(player), finished), "two gloves rejected");
        creative(player, ItemStack.EMPTY);
        helper.assertTrue(GloveSlot.get(player).isEmpty(), "creative packet clears the slot");
        // Vanilla slots still go through vanilla handling.
        creative(player, OFFHAND, new ItemStack(Items.SHIELD));
        helper.assertTrue(player.getOffhandItem().is(Items.SHIELD), "offhand creative edit unaffected");
        remove(player);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void respawnBroadcastsTheKeptGlove(GameTestHelper helper) {
        GameRules.BooleanValue keep = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean previous = keep.get();
        try {
            keep.set(true, helper.getLevel().getServer());
            Probe probe = probe(helper);
            ItemStack glove = new ItemStack(CsmcMod.GLOVES.get("specialist_gloves"));
            glove.set(SkinGameplay.SKIN, new SkinData(10033, 0.1F, 8));
            GloveSlot.set(probe.player(), glove.copy());
            probe.player().die(probe.player().damageSources().generic());
            probe.drain();
            ServerPlayer respawned = respawn(probe.player());
            List<GloveSyncPayload> sent = probe.drain();
            helper.assertTrue(!sent.isEmpty(), "a glove snapshot follows the respawn");
            for (GloveSyncPayload payload : sent) {
                helper.assertValueEqual(payload.entityId(), respawned.getId(), "snapshot for the new entity");
                helper.assertTrue(payload.glove().is(glove.getItem())
                        && glove.get(SkinGameplay.SKIN).equals(payload.glove().get(SkinGameplay.SKIN)),
                        "every post-respawn snapshot carries the kept glove: " + payload.glove());
            }
            remove(respawned);
        } finally {
            keep.set(previous, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void deathDropBroadcastsEmptyGlove(GameTestHelper helper) {
        GameRules.BooleanValue keep = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean previous = keep.get();
        try {
            keep.set(false, helper.getLevel().getServer());
            Probe probe = probe(helper);
            GloveSlot.set(probe.player(), new ItemStack(CsmcMod.GLOVES.get("motorcycle_gloves")));
            helper.assertTrue(probe.drain().get(0).glove().is(CsmcMod.GLOVES.get("motorcycle_gloves")),
                    "wearing broadcasts the glove");
            probe.player().die(probe.player().damageSources().generic());
            List<GloveSyncPayload> sent = probe.drain();
            helper.assertTrue(!sent.isEmpty(), "the death drop is broadcast");
            GloveSyncPayload last = sent.get(sent.size() - 1);
            helper.assertTrue(last.entityId() == probe.player().getId() && last.glove().isEmpty(),
                    "empty snapshot after the drop");
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(probe.player().blockPosition()).inflate(4),
                    drop -> drop.getItem().getItem() instanceof GloveItem).forEach(Entity::discard);
            remove(respawn(probe.player()));
        } finally {
            keep.set(previous, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void spectatorDeathKeepsGlove(GameTestHelper helper) {
        GameRules.BooleanValue keep = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean previous = keep.get();
        try {
            keep.set(false, helper.getLevel().getServer());
            ServerPlayer player = player(helper);
            ItemStack glove = new ItemStack(CsmcMod.GLOVES.get("studded_brokenfang_gloves"));
            GloveSlot.set(player, glove.copy());
            player.setGameMode(GameType.SPECTATOR);
            player.die(player.damageSources().generic());
            helper.assertTrue(ItemStack.matches(GloveSlot.get(player), glove), "spectator keeps the glove");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(player.blockPosition()).inflate(4), drop -> drop.getItem().is(glove.getItem())).isEmpty(),
                    "nothing dropped by a spectator");
            ServerPlayer respawned = respawn(player);
            helper.assertTrue(ItemStack.matches(GloveSlot.get(respawned), glove), "spectator respawns wearing it");
            remove(respawned);
        } finally {
            keep.set(previous, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    private static void creative(ServerPlayer player, ItemStack stack) {
        creative(player, GloveSlot.MENU_INDEX, stack);
    }

    private static void creative(ServerPlayer player, int slot, ItemStack stack) {
        player.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket((short) slot, stack));
    }

    /** A connected player whose client accepts glove snapshots; {@link #drain} returns those sent so far. */
    private record Probe(ServerPlayer player, EmbeddedChannel channel) {
        List<GloveSyncPayload> drain() {
            List<GloveSyncPayload> sent = new ArrayList<>();
            Object message;
            while ((message = channel.readOutbound()) != null) {
                if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload() instanceof GloveSyncPayload payload) {
                    sent.add(payload);
                }
            }
            return sent;
        }
    }

    /** Registers the glove channel as a csmc client's channel registration would; fake connections skip that step. */
    @SuppressWarnings("unchecked")
    private static void acceptGloveChannel(ServerPlayer player) {
        try {
            Field field = AbstractChanneledNetworkAddon.class.getDeclaredField("sendableChannels");
            field.setAccessible(true);
            ((Set<ResourceLocation>) field.get(ServerNetworkingImpl.getAddon(player.connection)))
                    .add(GloveSyncPayload.TYPE.id());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Probe probe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "probe" + level.getRandom().nextInt(100000)), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        // As a csmc client would after channel registration.
        acceptGloveChannel(player);
        player.setGameMode(GameType.SURVIVAL);
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
        player.teleportTo(level, at.x, at.y, at.z, 0.0F, 0.0F);
        player.setNoGravity(true);
        player.getInventory().clearContent();
        Probe probe = new Probe(player, channel);
        probe.drain();
        return probe;
    }

    private static int gloves(ServerPlayer player) {
        int count = GloveSlot.get(player).getCount();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() instanceof GloveItem) {
                count += player.getInventory().getItem(i).getCount();
            }
        }
        return count + (player.inventoryMenu.getCarried().getItem() instanceof GloveItem ? 1 : 0);
    }

    private static SkinCatalog.Kit kit(int id, String rarity, Set<String> weapons) {
        return new SkinCatalog.Kit(id, "fixture_glove_" + id, "Fixture Glove " + id, 0, 0F, 1F, rarity,
                weapons, weapons, false);
    }

    private static CraftingInput grid(ItemStack... occupied) {
        List<ItemStack> grid = new ArrayList<>(Collections.nCopies(4, ItemStack.EMPTY));
        for (int i = 0; i < occupied.length; i++) grid.set(i == 0 ? 0 : 3, occupied[i]);
        return CraftingInput.of(2, 2, grid);
    }

    /** A real, connected survival player at the test origin, with an empty inventory and no glove. */
    private static ServerPlayer player(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "glove" + level.getRandom().nextInt(100000)), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
        player.teleportTo(level, at.x, at.y, at.z, 0.0F, 0.0F);
        player.setNoGravity(true);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        return player;
    }

    private static ServerPlayer respawn(ServerPlayer dead) {
        return dead.server.getPlayerList().respawn(dead, false, Entity.RemovalReason.KILLED);
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
