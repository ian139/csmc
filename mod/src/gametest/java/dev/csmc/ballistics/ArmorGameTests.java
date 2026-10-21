package dev.csmc.ballistics;

import com.google.gson.JsonObject;
import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import dev.csmc.item.KevlarArmor;
import dev.csmc.melee.MeleeSystem;
import dev.csmc.network.KnifeActionPayload;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Fixed authored weapons keep armor expectations independent of the user's CS2 cache/balance overrides. */
public final class ArmorGameTests implements FabricGameTest {
    private static final double EPS = 1.0e-4;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void fixedAkAndAwpTierDamage(GameTestHelper helper) {
        Husk target = target(helper);
        Item[] tiers = {Items.AIR, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE,
                Items.GOLDEN_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE,
                Items.NETHERITE_CHESTPLATE, KevlarArmor.VEST};
        double[] ak = {40, 38.2, 36.85, 36.85, 35.5, 34.15, 32.8, 31};
        double[] awp = {40, 39.8, 39.65, 39.65, 39.5, 39.35, 39.2, 39};
        for (int i = 0; i < tiers.length; i++) {
            target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(tiers[i]));
            assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), ak[i]);
            assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.95), awp[i]);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void helmetBodyAndLegCoverage(GameTestHelper helper) {
        Husk target = target(helper);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(KevlarArmor.HELMET));
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.HEAD, 40, 1.55), 124);
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 40);
        target.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(KevlarArmor.VEST));
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.HEAD, 40, 1.55), 160);
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.STOMACH, 40, 1.55), 38.75);
        target.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        target.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.LEGS, 40, 1.55), 30);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void nonArmorAndWrongSlotGiveNoProtection(GameTestHelper helper) {
        Husk target = target(helper);
        for (Item item : new Item[]{Items.STONE, Items.CARVED_PUMPKIN, Items.DIAMOND_SWORD, Items.ELYTRA}) {
            target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(item));
            target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(item));
            assertDamage(helper, hit(helper, target, TraceResult.HitGroup.HEAD, 40, 1.55), 160);
            assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 40);
            helper.assertValueEqual(target.getItemBySlot(EquipmentSlot.CHEST).getDamageValue(), 0, "not worn");
        }
        target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_HELMET));
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 40);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void durabilityWearBreakAndRejectedHit(GameTestHelper helper) {
        Husk target = target(helper);
        ItemStack vest = new ItemStack(KevlarArmor.VEST);
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        ArmorModel.Absorption pure = ArmorModel.absorb(target, TraceResult.HitGroup.CHEST, 40, 1.55, 0.5);
        helper.assertTrue(pure.protectedHit() && pure.slot() == EquipmentSlot.CHEST, "covering vest");
        helper.assertValueEqual(pure.armorWear(), 4, "floor((40 - 31) * .5)");
        helper.assertValueEqual(vest.getDamageValue(), 0, "model is pure");
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 31);
        helper.assertValueEqual(vest.getDamageValue(), 4, "accepted hit wears only covering piece");
        target.setInvulnerable(true);
        helper.assertTrue(!hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55).hurt(), "rejected hit");
        helper.assertValueEqual(vest.getDamageValue(), 4, "rejected hit does not wear");
        target.setInvulnerable(false);
        vest.setDamageValue(vest.getMaxDamage() - 1);
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 31);
        helper.assertTrue(target.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "piece breaks");
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 40);
        ItemStack exhausted = new ItemStack(KevlarArmor.VEST);
        exhausted.setDamageValue(exhausted.getMaxDamage());
        target.setItemSlot(EquipmentSlot.CHEST, exhausted);
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 40);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void enchantmentsApplyOnceAfterCustomArmor(GameTestHelper helper) {
        Husk target = target(helper);
        var source = Ballistics.bulletSource(helper.getLevel(), null);
        helper.assertTrue(source.is(DamageTypeTags.IS_PROJECTILE) && source.is(DamageTypeTags.BYPASSES_ARMOR)
                && !source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS), "bullet tags");
        ItemStack vest = new ItemStack(KevlarArmor.VEST);
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        assertActualLoss(helper, target, 6.2);
        vest = new ItemStack(KevlarArmor.VEST);
        enchant(helper, vest, Enchantments.PROJECTILE_PROTECTION, 4);
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        assertActualLoss(helper, target, 4.216); // 31 * .2 * (1 - 8/25)
        vest = new ItemStack(KevlarArmor.VEST);
        enchant(helper, vest, Enchantments.PROTECTION, 4);
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        assertActualLoss(helper, target, 5.208); // 31 * .2 * (1 - 4/25)
        ItemStack helmet = new ItemStack(KevlarArmor.HELMET);
        enchant(helper, helmet, Enchantments.PROJECTILE_PROTECTION, 4);
        target.setItemSlot(EquipmentSlot.HEAD, helmet);
        assertActualLoss(helper, target, 3.224); // combined EPF 12; vanilla enchantments remain global
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unbreakingUsesVanillaDurabilityProcessing(GameTestHelper helper) {
        Husk target = target(helper);
        ItemStack vest = new ItemStack(KevlarArmor.VEST);
        enchant(helper, vest, Enchantments.UNBREAKING, 3);
        ItemStack reference = vest.copy();
        helper.getLevel().getRandom().setSeed(9471L);
        reference.hurtAndBreak(400, target, EquipmentSlot.CHEST);
        int expected = reference.getDamageValue();
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        helper.getLevel().getRandom().setSeed(9471L);
        hit(helper, target, TraceResult.HitGroup.CHEST, 1600, 1);
        helper.assertValueEqual(vest.getDamageValue(), expected, "same wear as vanilla hurtAndBreak");
        helper.assertTrue(expected > 0 && expected < 400, "Unbreaking actually saves durability: " + expected);
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_armor_pvp_off")
    public void pvpGuardDoesNotMutateArmor(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        boolean previous = server.isPvpAllowed();
        ServerPlayer attacker = helper.makeMockServerPlayerInLevel();
        ServerPlayer target = helper.makeMockServerPlayerInLevel();
        ItemStack vest = new ItemStack(KevlarArmor.VEST);
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        try {
            server.setPvpAllowed(false);
            float before = target.getHealth();
            var impact = Ballistics.hitEntity(helper.getLevel(), attacker,
                    Ballistics.bulletSource(helper.getLevel(), attacker), weapon(1.55), target, 40, 0,
                    target.position().add(0, target.getBbHeight() * .65, 0));
            helper.assertTrue(!impact.hurt() && impact.healthDamage() == 0 && target.getHealth() == before, "PvP guard");
            helper.assertValueEqual(vest.getDamageValue(), 0, "PvP guard precedes wear");
        } finally {
            server.setPvpAllowed(previous);
            remove(attacker);
            remove(target);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void kevlarHasIronVanillaDefenseAndRepair(GameTestHelper helper) {
        ArmorItem vest = KevlarArmor.VEST;
        ArmorItem helmet = KevlarArmor.HELMET;
        helper.assertValueEqual(vest.getDefense(), 6, "iron-equivalent vest vanilla defense");
        helper.assertValueEqual(helmet.getDefense(), 2, "iron-equivalent helmet vanilla defense");
        helper.assertTrue(vest.getToughness() == 0 && helmet.getToughness() == 0, "no toughness upgrade");
        helper.assertValueEqual(new ItemStack(vest).getMaxDamage(), 640, "vest durability");
        helper.assertValueEqual(new ItemStack(helmet).getMaxDamage(), 440, "helmet durability");
        helper.assertTrue(vest.isValidRepairItem(new ItemStack(vest), new ItemStack(Items.IRON_INGOT)), "iron repair");
        helper.assertTrue(!vest.isValidRepairItem(new ItemStack(vest), new ItemStack(Items.LEATHER)), "not leather repair");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void effectiveStackArmorAttributesDetermineEligibility(GameTestHelper helper) {
        Husk target = target(helper);
        ItemStack modded = new ItemStack(Items.STICK);
        modded.set(DataComponents.ATTRIBUTE_MODIFIERS, armorAttributes(6, EquipmentSlotGroup.CHEST));
        target.setItemSlot(EquipmentSlot.CHEST, modded);
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 38.2);
        modded.set(DataComponents.ATTRIBUTE_MODIFIERS, armorAttributes(6, EquipmentSlotGroup.HEAD));
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 40);
        ItemStack stripped = new ItemStack(KevlarArmor.VEST);
        stripped.set(DataComponents.ATTRIBUTE_MODIFIERS, armorAttributes(0, EquipmentSlotGroup.CHEST));
        target.setItemSlot(EquipmentSlot.CHEST, stripped);
        assertDamage(helper, hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55), 40);
        helper.assertValueEqual(stripped.getDamageValue(), 0, "zero-armor stack is not worn");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, batch = "csmc_armor_lethal", timeoutTicks = 120)
    public void lethalBulletAndKnifeBreakDroppedFinalDurabilityVest(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        boolean pvp = server.isPvpAllowed();
        boolean keep = level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        ServerPlayer attacker = helper.makeMockServerPlayerInLevel();
        ServerPlayer bulletVictim = helper.makeMockServerPlayerInLevel();
        ServerPlayer knifeVictim = helper.makeMockServerPlayerInLevel();
        for (ServerPlayer player : new ServerPlayer[]{attacker, bulletVictim, knifeVictim}) player.setNoGravity(true);
        // Real survival players are spawn-protected for their first 60 ticks.
        helper.runAfterDelay(65, () -> {
            try {
                server.setPvpAllowed(true);
                level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
                for (ServerPlayer victim : new ServerPlayer[]{bulletVictim, knifeVictim}) {
                    victim.setGameMode(GameType.SURVIVAL);
                    victim.getInventory().clearContent();
                    victim.setHealth(6);
                }
                // Keep death-drop assertions inside this fresh test structure, not the shared world spawn.
                Vec3 bulletAt = helper.absoluteVec(new Vec3(6, 1, 6));
                bulletVictim.moveTo(bulletAt.x, bulletAt.y, bulletAt.z, 0, 0);
                ItemStack bulletVest = new ItemStack(KevlarArmor.VEST);
                bulletVest.setDamageValue(639);
                bulletVictim.setItemSlot(EquipmentSlot.CHEST, bulletVest);
                var impact = Ballistics.hitEntity(level, attacker, Ballistics.bulletSource(level, attacker),
                        weapon(1.55), bulletVictim, 40, 0,
                        bulletVictim.position().add(0, bulletVictim.getBbHeight() * .65, 0));
                helper.assertTrue(impact.hurt() && bulletVictim.isDeadOrDying(), "lethal bullet accepted");
                helper.assertTrue(bulletVest.isEmpty(), "lethal bullet breaks the captured dropped vest");
                ItemStack knifeVest = new ItemStack(KevlarArmor.VEST);
                knifeVest.setDamageValue(639);
                knifeVictim.setItemSlot(EquipmentSlot.CHEST, knifeVest);
                attacker.setGameMode(GameType.SURVIVAL);
                attacker.getInventory().setItem(attacker.getInventory().selected, new ItemStack(CsmcMod.KNIVES.get("knife")));
                Vec3 at = helper.absoluteVec(new Vec3(2, 1, 2));
                attacker.moveTo(at.x, at.y, at.z, 0, 0);
                knifeVictim.moveTo(at.x, at.y, at.z + 1.5, 180, 0);
                knifeVictim.setYHeadRot(180);
                MeleeSystem.reset(attacker);
                var slash = MeleeSystem.handle(attacker, KnifeActionPayload.SLASH);
                helper.assertTrue(slash.hurt() && knifeVictim.isDeadOrDying(), "lethal knife accepted: " + slash);
                helper.assertTrue(knifeVest.isEmpty(), "lethal knife breaks the captured dropped vest");
                for (ServerPlayer victim : new ServerPlayer[]{bulletVictim, knifeVictim}) {
                    helper.assertTrue(victim.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "death cleared armor slot");
                    for (var dropped : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            victim.getBoundingBox().inflate(4))) {
                        helper.assertTrue(!dropped.getItem().is(KevlarArmor.VEST), "no intact vest survives in death drops");
                    }
                }
            } finally {
                server.setPvpAllowed(pvp);
                level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keep, server);
                remove(attacker);
                remove(bulletVictim);
                remove(knifeVictim);
            }
            helper.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void totemSavingBulletStillBreaksFinalDurabilityVest(GameTestHelper helper) {
        Husk target = target(helper);
        target.setHealth(1);
        target.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        ItemStack vest = new ItemStack(KevlarArmor.VEST);
        vest.setDamageValue(639);
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        var impact = Ballistics.hitEntity(helper.getLevel(), null, Ballistics.bulletSource(helper.getLevel(), null),
                weapon(1.55), target, 40, 0, target.position().add(0, target.getBbHeight() * .65, 0));
        helper.assertTrue(impact.hurt() && target.isAlive() && target.getHealth() == 1, "totem saves bullet victim");
        helper.assertTrue(target.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()
                && target.getAbsorptionAmount() == 8, "bullet consumes totem and grants absorption");
        helper.assertTrue(vest.isEmpty(), "totem-saving bullet breaks captured vest");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void totemSavingKnifeStillBreaksFinalDurabilityVest(GameTestHelper helper) {
        ServerPlayer attacker = helper.makeMockServerPlayerInLevel();
        try {
            attacker.setGameMode(GameType.SURVIVAL);
            attacker.getInventory().setItem(attacker.getInventory().selected, new ItemStack(CsmcMod.KNIVES.get("knife")));
            Vec3 at = helper.absoluteVec(new Vec3(2, 1, 2));
            attacker.moveTo(at.x, at.y, at.z, 0, 0);
            Husk target = target(helper);
            target.moveTo(at.x, at.y, at.z + 1.5, 180, 0);
            target.setYHeadRot(180);
            target.setHealth(1);
            target.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            ItemStack vest = new ItemStack(KevlarArmor.VEST);
            vest.setDamageValue(639);
            target.setItemSlot(EquipmentSlot.CHEST, vest);
            MeleeSystem.reset(attacker);
            var slash = MeleeSystem.handle(attacker, KnifeActionPayload.SLASH);
            helper.assertTrue(slash.hurt() && target.isAlive() && target.getHealth() == 1, "totem saves knife victim");
            helper.assertTrue(target.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()
                    && target.getAbsorptionAmount() == 8, "knife consumes totem and grants absorption");
            helper.assertTrue(vest.isEmpty(), "totem-saving knife breaks captured vest");
        } finally {
            remove(attacker);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakingCapturedStackDoesNotDamageReplacementEquipment(GameTestHelper helper) {
        ItemStack replacement = new ItemStack(KevlarArmor.VEST);
        Husk target = new Husk(EntityType.HUSK, helper.getLevel()) {
            @Override
            public boolean hurt(DamageSource source, float damage) {
                boolean accepted = super.hurt(source, damage);
                setItemSlot(EquipmentSlot.CHEST, replacement);
                return accepted;
            }
        };
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        ItemStack captured = new ItemStack(KevlarArmor.VEST);
        captured.setDamageValue(639);
        target.setItemSlot(EquipmentSlot.CHEST, captured);
        hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55);
        helper.assertTrue(captured.isEmpty(), "absorbing stack breaks after transfer");
        helper.assertTrue(target.getItemBySlot(EquipmentSlot.CHEST) == replacement, "replacement is not cleared");
        helper.assertValueEqual(replacement.getDamageValue(), 0, "replacement is not worn");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void bulletsDamageEndermenWithOrWithoutTeleportSpace(GameTestHelper helper) {
        EnderMan open = helper.spawnWithNoFreeWill(EntityType.ENDERMAN, new Vec3(2, 1, 2));
        EnderMan blocked = new EnderMan(EntityType.ENDERMAN, helper.getLevel()) {
            @Override
            protected boolean teleport() { return false; }
        };
        for (EnderMan target : new EnderMan[]{open, blocked}) {
            ItemStack vest = new ItemStack(KevlarArmor.VEST);
            enchant(helper, vest, Enchantments.PROJECTILE_PROTECTION, 4);
            target.setItemSlot(EquipmentSlot.CHEST, vest);
            float before = target.getHealth();
            var impact = Ballistics.hitEntity(helper.getLevel(), null, Ballistics.bulletSource(helper.getLevel(), null),
                    weapon(1.55), target, 40, 0, target.position().add(0, target.getBbHeight() * .65, 0));
            helper.assertTrue(impact.hurt(), "bullet accepted");
            near(helper, before - target.getHealth(), 4.216, "Enderman bullet damage with projectile protection");
            helper.assertValueEqual(vest.getDamageValue(), 4, "damaging bullet wears vest");
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void endermanProjectileDodgeDoesNotWearArmor(GameTestHelper helper) {
        EnderMan target = helper.spawnWithNoFreeWill(EntityType.ENDERMAN, new Vec3(2, 1, 2));
        target.getRandom().setSeed(9471L);
        ItemStack vest = new ItemStack(KevlarArmor.VEST);
        target.setItemSlot(EquipmentSlot.CHEST, vest);
        float before = target.getHealth();
        DamageSource arrow = new DamageSource(helper.getLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.ARROW));
        var impact = Ballistics.hitEntity(helper.getLevel(), null, arrow, weapon(1.55), target, 40, 0,
                target.position().add(0, target.getBbHeight() * .65, 0));
        helper.assertTrue(impact.hurt() && target.getHealth() == before, "vanilla successful projectile dodge");
        helper.assertValueEqual(vest.getDamageValue(), 0, "dodge causes no armor wear");
        helper.succeed();
    }

    private static ItemAttributeModifiers armorAttributes(double amount, EquipmentSlotGroup slot) {
        return ItemAttributeModifiers.builder().add(Attributes.ARMOR,
                new AttributeModifier(CsmcMod.id("armor_fixture"), amount, AttributeModifier.Operation.ADD_VALUE), slot).build();
    }

    private static void assertActualLoss(GameTestHelper helper, Husk target, double expected) {
        target.setHealth(200);
        hit(helper, target, TraceResult.HitGroup.CHEST, 40, 1.55);
        near(helper, 200 - target.getHealth(), expected, "actual enchanted health loss");
    }

    private static void enchant(GameTestHelper helper, ItemStack stack, ResourceKey<Enchantment> key, int level) {
        stack.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key), level);
    }

    private static TraceResult.EntityImpact hit(GameTestHelper helper, Husk target, TraceResult.HitGroup group,
                                                double damage, double ratio) {
        target.setHealth(200);
        double height = switch (group) {
            case HEAD -> .95;
            case CHEST, GENERIC -> .65;
            case STOMACH -> .45;
            case LEGS -> .2;
        };
        return Ballistics.hitEntity(helper.getLevel(), null, Ballistics.bulletSource(helper.getLevel(), null),
                weapon(ratio), target, damage, 0, target.position().add(0, target.getBbHeight() * height, 0));
    }

    private static void assertDamage(GameTestHelper helper, TraceResult.EntityImpact impact, double expected) {
        near(helper, impact.csDamage(), expected, "CS damage");
        near(helper, impact.healthDamage(), expected * .2, "requested MC damage");
    }

    private static void near(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Math.abs(actual - expected) < EPS, label + ": expected " + expected + ", got " + actual);
    }

    private static WeaponData weapon(double ratio) {
        JsonObject json = new JsonObject();
        json.addProperty("damage", 40);
        json.addProperty("magazine_size", 30);
        json.addProperty("cycle_time_s", .1);
        json.addProperty("headshot_multiplier", 4);
        json.addProperty("armor_ratio", ratio);
        json.addProperty("armor_bonus", .5);
        return WeaponData.fromJson(CsmcMod.id("armor_fixture"), json);
    }

    private static Husk target(GameTestHelper helper) {
        Husk target = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(2, 1, 2));
        for (EquipmentSlot slot : EquipmentSlot.values()) target.setItemSlot(slot, ItemStack.EMPTY);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.setHealth(200);
        target.setNoGravity(true);
        return target;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
