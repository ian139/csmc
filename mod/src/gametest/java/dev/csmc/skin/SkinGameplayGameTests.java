package dev.csmc.skin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.csmc.CsmcMod;
import dev.csmc.skin.gameplay.CaseOpeningPayload;
import dev.csmc.skin.gameplay.SkinApplyPayload;
import dev.csmc.skin.gameplay.SkinPicker;
import dev.csmc.skin.gameplay.CaseScroll;
import dev.csmc.skin.gameplay.SkinCommands;
import dev.csmc.skin.gameplay.SkinCraftingRecipe;
import dev.csmc.skin.gameplay.SkinGameplay;
import dev.csmc.skin.gameplay.WeaponCaseItem;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;

public final class SkinGameplayGameTests implements FabricGameTest {
    private static final SkinData OLD_SKIN = new SkinData(910001, .17f, 23);
    private static final SkinData NEW_SKIN = new SkinData(910002, .63f, 947);
    private static final String UNKNOWN_WEAPON = "csmc_fixture_unknown_weapon";
    private static final SkinCatalog.Kit UNAVAILABLE = new SkinCatalog.Kit(
            910004, "fixture_unavailable", "Fixture Unavailable", 0, .1f, .9f, "common",
            Set.of("glock", "ak47"), Set.of(), false);
    private static final SkinCatalog.Kit UNKNOWN = new SkinCatalog.Kit(
            910005, "fixture_unknown", "Fixture Unknown", 0, 0, 1, "common",
            Set.of(UNKNOWN_WEAPON), Set.of(UNKNOWN_WEAPON), false);
    // Self-authored kits only: no catalog reads, cache files, or global catalog mutation.
    private static final List<SkinCatalog.Kit> CASE_KITS = List.of(
            new SkinCatalog.Kit(910001, "fixture_pistol", "Fixture Pistol", 0, .11f, .19f, "common",
                    Set.of("glock", "ak47"), Set.of("glock"), false),
            new SkinCatalog.Kit(910002, "fixture_rifle", "Fixture Rifle", 0, .4f, .7f, "common",
                    Set.of("ak47"), Set.of("ak47"), false),
            new SkinCatalog.Kit(910003, "fixture_shared", "Fixture Shared", 0, .02f, .98f, "common",
                    Set.of("glock", "ak47"), Set.of("glock", "ak47"), false),
            UNAVAILABLE, UNKNOWN);
    // Explicit uniform rarity weights: case rolls never consult the global catalog rarity table.
    private static final ToLongFunction<String> UNIFORM = rarity -> 1L;
    // Self-authored Glock kits sharing one display name, told apart only by their internal phase/gem names.
    private static final List<SkinCatalog.Kit> GAMMA_KITS = List.of(
            new SkinCatalog.Kit(1119, "am_emerald_marbleized_glock", "Gamma Doppler", 0, 0, .08f, "legendary",
                    Set.of("glock"), Set.of("glock"), false),
            new SkinCatalog.Kit(1122, "am_gamma_doppler_phase3_glock", "Gamma Doppler", 0, 0, .08f, "legendary",
                    Set.of("glock"), Set.of("glock"), false));
    private static final String KNIFE = "knife_karambit";
    // Knife kit precedes the Glock kit: both resolve to "Gamma Doppler (Phase 3)", so display names must follow the held weapon.
    private static final List<SkinCatalog.Kit> MIXED_GAMMA_KITS = List.of(
            new SkinCatalog.Kit(571, "am_gamma_doppler_phase3", "Gamma Doppler", 0, 0, .08f, "legendary",
                    Set.of(KNIFE), Set.of(KNIFE), false),
            new SkinCatalog.Kit(1122, "am_gamma_doppler_phase3_glock", "Gamma Doppler", 0, 0, .08f, "legendary",
                    Set.of("glock"), Set.of("glock"), false));

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void applyPreservesMagazineNameAndInputsInBothGrids(GameTestHelper helper) {
        for (int width : new int[] {2, 3}) {
            for (String weapon : List.of("glock", "ak47")) {
                for (int slot = 0; slot < width * width; slot++) {
                    assertCraft(helper, width, slot, weapon, null, NEW_SKIN);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void swapReturnsOldAttachmentAtGunSlotInBothGrids(GameTestHelper helper) {
        for (int width : new int[] {2, 3}) {
            for (String weapon : List.of("glock", "ak47")) {
                for (int slot = 0; slot < width * width; slot++) {
                    assertCraft(helper, width, slot, weapon, OLD_SKIN, NEW_SKIN);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void removalReturnsOldAttachmentAndPreservesGunInBothGrids(GameTestHelper helper) {
        for (int width : new int[] {2, 3}) {
            for (String weapon : List.of("glock", "ak47")) {
                for (int slot = 0; slot < width * width; slot++) {
                    assertCraft(helper, width, slot, weapon, OLD_SKIN, null);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void mismatchExtrasAndIncompleteInputsAreRejectedInBothGrids(GameTestHelper helper) {
        for (int width : new int[] {2, 3}) {
            ItemStack gun = gun("glock", OLD_SKIN);
            ItemStack attachment = SkinGameplay.skin("glock", NEW_SKIN);
            assertRejected(helper, input(width, gun, SkinGameplay.skin("ak47", NEW_SKIN)), "weapon mismatch");
            assertRejected(helper, input(width, gun, attachment, new ItemStack(Items.STICK)), "extra item");
            assertRejected(helper, input(width, gun, attachment, SkinGameplay.skin("glock", OLD_SKIN)), "two attachments");
            assertRejected(helper, input(width, gun, attachment, gun("glock", null)), "two guns");
            assertRejected(helper, input(width, gun, new ItemStack(Items.STICK)), "removal with extra item");
            assertRejected(helper, input(width, gun("glock", null)), "unskinned gun alone");
            assertRejected(helper, input(width, attachment), "attachment without gun");
            assertRejected(helper, input(width), "empty grid");

            ItemStack noData = new ItemStack(SkinGameplay.WEAPON_SKIN);
            noData.set(SkinGameplay.SKIN_WEAPON, "glock");
            assertRejected(helper, input(width, gun, noData), "attachment missing skin data");
            ItemStack noWeapon = new ItemStack(SkinGameplay.WEAPON_SKIN);
            noWeapon.set(SkinGameplay.SKIN, NEW_SKIN);
            assertRejected(helper, input(width, gun, noWeapon), "attachment missing weapon");
            ItemStack wrongItem = new ItemStack(Items.STICK);
            wrongItem.set(SkinGameplay.SKIN, NEW_SKIN);
            wrongItem.set(SkinGameplay.SKIN_WEAPON, "glock");
            assertRejected(helper, input(width, gun, wrongItem), "skin components on unrelated item");
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void recipeFitsInventoryAndTableButNotNarrowGrids(GameTestHelper helper) {
        SkinCraftingRecipe recipe = recipe();
        helper.assertTrue(recipe.canCraftInDimensions(2, 2), "recipe fits inventory crafting");
        helper.assertTrue(recipe.canCraftInDimensions(3, 3), "recipe fits crafting table");
        helper.assertTrue(!recipe.canCraftInDimensions(1, 1), "recipe rejects 1x1 grid");
        helper.assertTrue(!recipe.canCraftInDimensions(1, 3), "recipe rejects narrow grid");
        helper.assertTrue(!recipe.canCraftInDimensions(3, 1), "recipe rejects short grid");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseRollsAreSeededAndRespectAvailabilityWearAndSeedBounds(GameTestHelper helper) {
        helper.assertTrue(CsmcMod.GUNS.containsKey("glock") && CsmcMod.GUNS.containsKey("ak47"),
                "fixture weapons must be registered");
        helper.assertTrue(!CsmcMod.GUNS.containsKey(UNKNOWN_WEAPON), "fixture unknown weapon must not be registered");
        RandomSource first = RandomSource.create(0x43534d43L);
        RandomSource second = RandomSource.create(0x43534d43L);
        for (int draw = 0; draw < 512; draw++) {
            Optional<ItemStack> rolled = WeaponCaseItem.roll(CASE_KITS, first, UNIFORM);
            Optional<ItemStack> repeated = WeaponCaseItem.roll(CASE_KITS, second, UNIFORM);
            helper.assertTrue(rolled.isPresent() && repeated.isPresent(), "valid fixtures yield an attachment");
            ItemStack stack = rolled.orElseThrow();
            helper.assertTrue(ItemStack.matches(stack, repeated.orElseThrow()), "same seed reproduces roll " + draw);
            helper.assertTrue(stack.is(SkinGameplay.WEAPON_SKIN), "case returns the attachment item");
            helper.assertValueEqual(stack.getCount(), 1, "case attachment count");
            SkinData data = stack.get(SkinGameplay.SKIN);
            String weapon = stack.get(SkinGameplay.SKIN_WEAPON);
            helper.assertTrue(data != null && weapon != null, "case attachment has both skin components");
            helper.assertTrue(CsmcMod.GUNS.containsKey(weapon), "case chooses a registered weapon");
            Optional<SkinCatalog.Kit> fixture = CASE_KITS.stream()
                    .filter(kit -> kit.paintKit() == data.paintKit()).findFirst();
            helper.assertTrue(fixture.isPresent(), "case chooses only an explicit fixture kit");
            SkinCatalog.Kit kit = fixture.orElseThrow();
            helper.assertTrue(kit.supports(weapon) && kit.availableFor(weapon), "case requires available weapon assets");
            helper.assertTrue(Float.isFinite(data.wear()) && data.wear() >= kit.wearMin() && data.wear() <= kit.wearMax(),
                    "case wear lies in selected kit bounds");
            helper.assertTrue(data.seed() >= 0 && data.seed() <= 1000, "case pattern seed lies in [0,1000]");
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseAcceptsFixedWearIncludingEndpoints(GameTestHelper helper) {
        for (float wear : new float[] {0, .38f, 1}) {
            SkinCatalog.Kit fixed = new SkinCatalog.Kit(
                    910006, "fixture_fixed", "Fixture Fixed", 0, wear, wear, "common",
                    Set.of("glock"), Set.of("glock"), false);
            RandomSource random = RandomSource.create(91);
            for (int draw = 0; draw < 32; draw++) {
                Optional<ItemStack> rolled = WeaponCaseItem.roll(List.of(fixed), random, UNIFORM);
                helper.assertTrue(rolled.isPresent(), "equal wear bounds remain eligible");
                SkinData data = rolled.orElseThrow().get(SkinGameplay.SKIN);
                helper.assertTrue(data != null, "fixed-wear attachment carries skin data");
                helper.assertValueEqual(data.wear(), wear, "fixed kit wear");
                helper.assertTrue(data.seed() >= 0 && data.seed() <= 1000, "fixed-wear pattern seed bounds");
            }
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void emptyAndUnsupportedCaseFixturesReturnEmpty(GameTestHelper helper) {
        for (int seed = 0; seed < 16; seed++) {
            helper.assertTrue(WeaponCaseItem.roll(List.of(), RandomSource.create(seed), UNIFORM).isEmpty(),
                    "empty fixture list yields nothing");
            helper.assertTrue(WeaponCaseItem.roll(List.of(UNAVAILABLE), RandomSource.create(seed), UNIFORM).isEmpty(),
                    "supported but unavailable assets yield nothing");
            helper.assertTrue(WeaponCaseItem.roll(List.of(UNKNOWN), RandomSource.create(seed), UNIFORM).isEmpty(),
                    "available assets for an unknown weapon yield nothing");
            helper.assertTrue(WeaponCaseItem.roll(List.of(UNAVAILABLE, UNKNOWN), RandomSource.create(seed), UNIFORM)
                    .isEmpty(), "mixed unsupported fixtures yield nothing");
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseRarityTierFollowsInjectedWeightsExactly(GameTestHelper helper) {
        // One supported+available pair per tier: weights common 1, rare 3 give a [0, 0.25) common band.
        SkinCatalog.Kit common = new SkinCatalog.Kit(910007, "fixture_common", "Fixture Common", 0, .2f, .3f,
                "common", Set.of("glock"), Set.of("glock"), false);
        SkinCatalog.Kit rare = new SkinCatalog.Kit(910008, "fixture_rare", "Fixture Rare", 0, .2f, .3f,
                "rare", Set.of("glock"), Set.of("glock"), false);
        List<SkinCatalog.Kit> kits = List.of(common, rare);
        ToLongFunction<String> weights = rarity -> rarity.equals("common") ? 1L : 3L;
        for (int seed = 0; seed < 32; seed++) {
            double draw = RandomSource.create(seed).nextDouble();
            int expected = draw < .25 ? common.paintKit() : rare.paintKit();
            Optional<ItemStack> rolled = WeaponCaseItem.roll(kits, RandomSource.create(seed), weights);
            helper.assertTrue(rolled.isPresent(), "weighted fixtures yield an attachment for seed " + seed);
            SkinData data = rolled.orElseThrow().get(SkinGameplay.SKIN);
            helper.assertTrue(data != null, "weighted attachment carries skin data for seed " + seed);
            helper.assertValueEqual(data.paintKit(), expected, "tier for seed " + seed + " with draw " + draw);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void numericSkinCommandWorksWithoutCatalogAndPreservesHeldGun(GameTestHelper helper) {
        Grant random = grant(helper, List.of(), "glock", "csmc skin 910042");
        assertGranted(helper, random, "glock", 910042, 0f, null, "absent catalog defaults wear to 0");
        Grant explicit = grant(helper, List.of(), "glock", "csmc skin 910042 0.25 77");
        assertGranted(helper, explicit, "glock", 910042, .25f, 77, "absent catalog explicit wear and seed");
        Grant bounds = grant(helper, List.of(), "ak47", "csmc skin 0 1 1000");
        assertGranted(helper, bounds, "ak47", 0, 1f, 1000, "inclusive id, wear and seed bounds");
        Grant lowBounds = grant(helper, List.of(), "ak47", "csmc skin +3 0 0");
        assertGranted(helper, lowBounds, "ak47", 3, 0f, 0, "explicit sign and zero wear and seed");

        for (String id : List.of("-1", "2147483648")) {
            Grant rejected = grant(helper, List.of(), "glock", "csmc skin " + id);
            assertRejectedGrant(helper, rejected, "commands.csmc.skin.invalid_id", "paint kit id " + id);
        }
        for (String arguments : List.of("5 1.5", "5 -0.1", "5 0.5 1001", "5 0.5 -1")) {
            assertSyntaxRejected(helper, List.of(), "csmc skin " + arguments, "out-of-range " + arguments);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void namedSkinCommandRequiresCatalog(GameTestHelper helper) {
        Grant rejected = grant(helper, List.of(), "glock", "csmc skin fixture_pistol");
        assertRejectedGrant(helper, rejected, "commands.csmc.skin.catalog_unavailable", "absent catalog name");
        Grant unknown = grant(helper, CASE_KITS, "glock", "csmc skin fixture_missing");
        assertRejectedGrant(helper, unknown, "commands.csmc.skin.unknown", "unknown name with catalog");
        Grant named = grant(helper, CASE_KITS, "glock", "csmc skin \"Fixture Pistol\" 0.5 12");
        assertGranted(helper, named, "glock", 910001, .5f, 12, "quoted display name with catalog");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void skinCommandMatchesAndReportsPhaseAwareNames(GameTestHelper helper) {
        Grant phase = grant(helper, GAMMA_KITS, "glock", "csmc skin \"gamma doppler (phase 3)\" 0.05 9");
        assertGranted(helper, phase, "glock", 1122, .05f, 9, "phase-aware display name");
        helper.assertValueEqual(successLabel(helper, phase), "Gamma Doppler (Phase 3)", "phase-aware success label");
        Grant gem = grant(helper, GAMMA_KITS, "glock", "csmc skin \"Gamma Doppler (Emerald)\"");
        assertGranted(helper, gem, "glock", 1119, 0f, null, "gem display name");
        helper.assertValueEqual(successLabel(helper, gem), "Gamma Doppler (Emerald)", "gem success label");
        Grant internal = grant(helper, GAMMA_KITS, "glock", "csmc skin am_gamma_doppler_phase3_glock");
        assertGranted(helper, internal, "glock", 1122, 0f, null, "internal name");
        Grant shared = grant(helper, GAMMA_KITS, "glock", "csmc skin \"Gamma Doppler\"");
        assertGranted(helper, shared, "glock", 1119, 0f, null, "unqualified shared name keeps its first match");
        helper.assertValueEqual(successLabel(helper, shared), "Gamma Doppler (Emerald)", "shared name reports the gem");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void sharedDisplayNamesResolveToTheKitSupportingTheHeldWeapon(GameTestHelper helper) {
        String phase = "csmc skin \"Gamma Doppler (Phase 3)\"";
        assertGranted(helper, grant(helper, MIXED_GAMMA_KITS, "glock", phase), "glock", 1122, 0f, null,
                "held Glock skips the earlier knife kit");
        assertGranted(helper, grant(helper, MIXED_GAMMA_KITS, KNIFE, phase), KNIFE, 571, 0f, null,
                "held knife picks the knife kit");
        String base = "csmc skin \"Gamma Doppler\"";
        assertGranted(helper, grant(helper, MIXED_GAMMA_KITS, "glock", base), "glock", 1122, 0f, null,
                "base display name follows the held Glock");
        assertGranted(helper, grant(helper, MIXED_GAMMA_KITS, KNIFE, base), KNIFE, 571, 0f, null,
                "base display name follows the held knife");
        assertRejectedGrant(helper, grant(helper, MIXED_GAMMA_KITS, "ak47", phase), "commands.csmc.skin.unknown",
                "display name without a kit for the held weapon");
        // Explicit IDs and internal names stay specific and are still validated against the held weapon.
        assertGranted(helper, grant(helper, MIXED_GAMMA_KITS, KNIFE, "csmc skin am_gamma_doppler_phase3"),
                KNIFE, 571, 0f, null, "knife internal name");
        assertGranted(helper, grant(helper, MIXED_GAMMA_KITS, "glock", "csmc skin 1122"),
                "glock", 1122, 0f, null, "Glock numeric id");
        for (String query : List.of("571", "am_gamma_doppler_phase3")) {
            assertRejectedGrant(helper, grant(helper, MIXED_GAMMA_KITS, "glock", "csmc skin " + query),
                    "commands.csmc.skin.unsupported", "knife kit on Glock " + query);
        }
        for (String query : List.of("1122", "am_gamma_doppler_phase3_glock")) {
            assertRejectedGrant(helper, grant(helper, MIXED_GAMMA_KITS, KNIFE, "csmc skin " + query),
                    "commands.csmc.skin.unsupported", "Glock kit on knife " + query);
        }
        helper.succeed();
    }

    private static Object successLabel(GameTestHelper helper, Grant grant) {
        for (Component message : grant.messages()) {
            if (message.getContents() instanceof TranslatableContents translatable
                    && translatable.getKey().equals("commands.csmc.skin.success")) {
                return translatable.getArgs()[0];
            }
        }
        throw new GameTestAssertException("no success feedback: " + grant.messages());
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void knownSkinMetadataStillRejectsUnsupportedWeapon(GameTestHelper helper) {
        for (String query : List.of("910002", "fixture_rifle")) {
            Grant rejected = grant(helper, CASE_KITS, "glock", "csmc skin " + query);
            assertRejectedGrant(helper, rejected, "commands.csmc.skin.unsupported", "rifle-only kit " + query);
        }
        Grant supported = grant(helper, CASE_KITS, "ak47", "csmc skin 910002");
        assertGranted(helper, supported, "ak47", 910002, .4f, null, "known kit defaults to wearMin");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void skinCommandIgnoresLocalTextureAvailability(GameTestHelper helper) {
        // UNAVAILABLE supports glock but has no local assets; debug grants never require them.
        Grant numeric = grant(helper, CASE_KITS, "glock", "csmc skin 910004");
        assertGranted(helper, numeric, "glock", 910004, .1f, null, "unavailable kit by id");
        Grant named = grant(helper, CASE_KITS, "glock", "csmc skin fixture_unavailable 0.9 400");
        assertGranted(helper, named, "glock", 910004, .9f, 400, "unavailable kit by name");
        Grant unknownId = grant(helper, CASE_KITS, "glock", "csmc skin 123456");
        assertGranted(helper, unknownId, "glock", 123456, 0f, null, "id without metadata beside a catalog");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void skinCommandRequiresOperatorPermission(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(SkinCommands.command(List.of()));
        ServerPlayer player = player(helper, "glock");
        try {
            CommandSourceStack source = player.createCommandSourceStack().withPermission(1).withSource(new Capture());
            boolean denied = false;
            try {
                dispatcher.execute("csmc skin 910042", source);
            } catch (CommandSyntaxException expected) {
                denied = true;
            }
            helper.assertTrue(denied, "permission level 1 cannot reach /csmc skin");
            helper.assertValueEqual(attachments(player).size(), 0, "denied command grants nothing");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void skinnedGunTooltipShowsItsSkinLikeTheAttachment(GameTestHelper helper) {
        ItemStack skinned = gun("ak47", OLD_SKIN);
        List<Component> gunLines = tooltip(skinned);
        helper.assertTrue(hasKey(gunLines, "tooltip.csmc.magazine"), "skinned gun keeps its magazine line");
        Component kit = line(helper, gunLines, "tooltip.csmc.skin.kit");
        Object kitName = ((TranslatableContents) kit.getContents()).getArgs()[0];
        helper.assertTrue(kitName instanceof Component name && hasKey(name, "tooltip.csmc.skin.unknown_kit")
                && List.of(((TranslatableContents) name.getContents()).getArgs()).contains(910001),
                "kit line names paint kit 910001");
        Object[] wear = ((TranslatableContents) line(helper, gunLines, "tooltip.csmc.skin.wear").getContents()).getArgs();
        helper.assertValueEqual(List.of(wear), List.of("Field-Tested", "0.17"), "wear tier and float");
        Object[] seed = ((TranslatableContents) line(helper, gunLines, "tooltip.csmc.skin.seed").getContents()).getArgs();
        helper.assertValueEqual(List.of(seed), List.<Object>of(23), "pattern seed");

        // Same formatting as the attachment, minus the attachment's own weapon line.
        List<Component> attachmentLines = new ArrayList<>(tooltip(SkinGameplay.skin("ak47", OLD_SKIN)));
        attachmentLines.removeIf(component -> hasKey(component, "tooltip.csmc.skin.weapon"));
        helper.assertValueEqual(gunLines.stream().filter(SkinGameplayGameTests::isSkinLine).toList(), attachmentLines,
                "gun skin lines match the attachment's");

        List<Component> plain = tooltip(gun("ak47", null));
        helper.assertTrue(plain.stream().noneMatch(SkinGameplayGameTests::isSkinLine), "unskinned gun has no skin lines");
        helper.succeed();
    }

    private static List<Component> tooltip(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        stack.getItem().appendHoverText(stack, Item.TooltipContext.EMPTY, lines, TooltipFlag.NORMAL);
        return lines;
    }

    private static boolean isSkinLine(Component component) {
        return component.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().startsWith("tooltip.csmc.skin.");
    }

    private static Component line(GameTestHelper helper, List<Component> lines, String key) {
        for (Component component : lines) {
            if (component.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key)) {
                return component;
            }
        }
        throw new GameTestAssertException("tooltip lacks " + key + ": " + lines);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void openingCaseConsumesOneAndGrantsOneAttachment(GameTestHelper helper) {
        SkinCatalog.Kit kit = new SkinCatalog.Kit(910009, "fixture_case", "Fixture Case", 0, .2f, .3f, "common",
                Set.of("glock"), Set.of("glock"), false);
        ServerPlayer player = survivalPlayer(helper);
        try {
            // A last case turns into the attachment in the same hand.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.WEAPON_CASE));
            InteractionResultHolder<ItemStack> last = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND,
                    WeaponCaseItem.Pool.ALL, List.of(kit), RandomSource.create(5), UNIFORM);
            helper.assertValueEqual(last.getResult(), InteractionResult.CONSUME, "last case is opened");
            player.setItemInHand(InteractionHand.MAIN_HAND, last.getObject());
            assertCaseAttachment(helper, player.getMainHandItem(), kit, "last case result in hand");
            helper.assertValueEqual(countOf(player, SkinGameplay.WEAPON_CASE), 0, "last case consumed");

            // One of several cases: the rest stay in hand and the attachment goes to the inventory.
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.WEAPON_CASE, 3));
            InteractionResultHolder<ItemStack> stacked = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND,
                    WeaponCaseItem.Pool.ALL, List.of(kit), RandomSource.create(6), UNIFORM);
            helper.assertValueEqual(stacked.getResult(), InteractionResult.CONSUME, "stacked case is opened");
            player.setItemInHand(InteractionHand.MAIN_HAND, stacked.getObject());
            helper.assertValueEqual(countOf(player, SkinGameplay.WEAPON_CASE), 2, "exactly one of three cases consumed");
            List<ItemStack> granted = attachments(player);
            helper.assertValueEqual(granted.size(), 1, "one attachment granted from a stack");
            assertCaseAttachment(helper, granted.get(0), kit, "stacked case attachment");

            // Nothing available: the case stays and nothing is granted.
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.WEAPON_CASE));
            InteractionResultHolder<ItemStack> empty = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND,
                    WeaponCaseItem.Pool.ALL, List.of(UNAVAILABLE), RandomSource.create(7), UNIFORM);
            helper.assertValueEqual(empty.getResult(), InteractionResult.FAIL, "empty case pool fails");
            helper.assertValueEqual(countOf(player, SkinGameplay.WEAPON_CASE), 1, "empty case pool keeps the case");
            helper.assertValueEqual(attachments(player).size(), 0, "empty case pool grants nothing");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseReelPlacesTheGrantedResultAtTheLandingIndex(GameTestHelper helper) {
        for (long seed = 0; seed < 32; seed++) {
            WeaponCaseItem.Rolled rolled = WeaponCaseItem.rollResult(CASE_KITS, RandomSource.create(seed), UNIFORM).orElseThrow();
            CaseOpeningPayload reel = CaseOpeningPayload.create(WeaponCaseItem.Pool.ALL, rolled, CASE_KITS,
                    CsmcMod.GUNS.keySet(), seed * 31 + 7, UNIFORM);
            helper.assertValueEqual(reel.pool(), WeaponCaseItem.Pool.ALL, "reel names the opened case");
            helper.assertValueEqual(reel.strip().size(), CaseOpeningPayload.STRIP_LENGTH, "strip length");
            helper.assertValueEqual(reel.landingIndex(), CaseOpeningPayload.LANDING_INDEX, "fixed landing index");
            CaseOpeningPayload.Entry landed = reel.resultEntry();
            helper.assertValueEqual(landed.weapon(), rolled.pick().weapon(), "landing weapon");
            helper.assertValueEqual(landed.paintKit(), rolled.data().paintKit(), "landing paint kit");
            helper.assertValueEqual(landed.name(), SkinNames.displayName(rolled.pick().kit()), "landing name");
            helper.assertValueEqual(reel.result(), rolled.data(), "result wear and seed");
            helper.assertValueEqual(rolled.stack().get(SkinGameplay.SKIN), rolled.data(), "granted stack matches the reel");
            for (CaseOpeningPayload.Entry entry : reel.strip()) {
                helper.assertTrue(CASE_KITS.stream().anyMatch(kit -> kit.paintKit() == entry.paintKit()
                        && kit.availableFor(entry.weapon())), "filler draws only openable pairs: " + entry);
            }

            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            CaseOpeningPayload.STREAM_CODEC.encode(buf, reel);
            helper.assertValueEqual(CaseOpeningPayload.STREAM_CODEC.decode(buf), reel, "codec round trip");

            // The case kind survives the wire, so the client titles the reel after the opened case.
            CaseOpeningPayload loadout = new CaseOpeningPayload(WeaponCaseItem.Pool.LOADOUT, reel.strip(),
                    reel.landingIndex(), reel.result());
            RegistryFriendlyByteBuf loadoutBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            CaseOpeningPayload.STREAM_CODEC.encode(loadoutBuf, loadout);
            CaseOpeningPayload decoded = CaseOpeningPayload.STREAM_CODEC.decode(loadoutBuf);
            helper.assertValueEqual(decoded, loadout, "loadout codec round trip");
            helper.assertValueEqual(decoded.pool(), WeaponCaseItem.Pool.LOADOUT, "loadout pool round trip");
        }

        // A hostile oversize strip is rejected before allocating entries.
        RegistryFriendlyByteBuf oversize = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        oversize.writeByte(WeaponCaseItem.Pool.ALL.ordinal());
        oversize.writeVarInt(CaseOpeningPayload.MAX_STRIP + 1);
        boolean rejected = false;
        try {
            CaseOpeningPayload.STREAM_CODEC.decode(oversize);
        } catch (DecoderException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "oversize strip rejected");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseReelNamesSharedDopplerKitsByPhase(GameTestHelper helper) {
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 16; seed++) {
            WeaponCaseItem.Rolled rolled = WeaponCaseItem.rollResult(GAMMA_KITS, RandomSource.create(seed), UNIFORM).orElseThrow();
            CaseOpeningPayload reel = CaseOpeningPayload.create(WeaponCaseItem.Pool.ALL, rolled, GAMMA_KITS,
                    CsmcMod.GUNS.keySet(), seed * 17 + 3, UNIFORM);
            for (CaseOpeningPayload.Entry entry : reel.strip()) {
                String expected = entry.paintKit() == 1119 ? "Gamma Doppler (Emerald)" : "Gamma Doppler (Phase 3)";
                helper.assertValueEqual(entry.name(), expected, "reel tile name for kit " + entry.paintKit());
                seen.add(entry.name());
            }
        }
        helper.assertValueEqual(seen, Set.of("Gamma Doppler (Emerald)", "Gamma Doppler (Phase 3)"), "both kits on the reel");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseReelCodecRejectsNegativePaintKitsAndEarlyLandings(GameTestHelper helper) {
        // Regression: a negative filler paint kit crashed the client when it built the tile's SkinData.
        helper.assertTrue(decodeRejected(rawReel(0, 10, 7, -5)), "negative filler paint kit rejected");
        helper.assertTrue(decodeRejected(rawReel(0, 10, 7, 0)), "zero filler paint kit rejected");
        // Regression: landing indices before the reel start crashed the scroll.
        for (int landing = 0; landing < CaseScroll.MIN_LANDING_INDEX; landing++) {
            helper.assertTrue(decodeRejected(rawReel(0, 10, landing, 910001)), "landing " + landing + " rejected");
        }
        helper.assertTrue(!decodeRejected(rawReel(0, 10, CaseScroll.MIN_LANDING_INDEX, 910001)),
                "first valid landing index accepted");
        // The case kind is bounded to the known pools.
        for (WeaponCaseItem.Pool pool : WeaponCaseItem.Pool.values()) {
            helper.assertTrue(!decodeRejected(rawReel(pool.ordinal(), 10, 7, 910001)), "pool " + pool + " accepted");
        }
        helper.assertTrue(decodeRejected(rawReel(-1, 10, 7, 910001)), "negative pool rejected");
        helper.assertTrue(decodeRejected(rawReel(WeaponCaseItem.Pool.values().length, 10, 7, 910001)),
                "unknown pool rejected");
        helper.succeed();
    }

    /**
     * Wire bytes of a {@code size}-tile reel of case pool ordinal {@code pool} landing on {@code landing}; the first
     * filler tile uses {@code fillerKit}.
     */
    private static RegistryFriendlyByteBuf rawReel(int pool, int size, int landing, int fillerKit) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.EMPTY);
        buf.writeByte(pool);
        buf.writeVarInt(size);
        int filler = landing == 0 ? 1 : 0;
        for (int i = 0; i < size; i++) {
            buf.writeUtf("glock");
            buf.writeVarInt(i == filler ? fillerKit : 910001);
            buf.writeUtf("Fixture");
            buf.writeByte(0);
        }
        buf.writeVarInt(landing);
        SkinData.STREAM_CODEC.encode(buf, OLD_SKIN);
        return buf;
    }

    private static boolean decodeRejected(RegistryFriendlyByteBuf buf) {
        try {
            CaseOpeningPayload.STREAM_CODEC.decode(buf);
            return false;
        } catch (DecoderException expected) {
            return true;
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseReelIsDeterministicPerSeed(GameTestHelper helper) {
        WeaponCaseItem.Rolled rolled = WeaponCaseItem.rollResult(CASE_KITS, RandomSource.create(3), UNIFORM).orElseThrow();
        CaseOpeningPayload first = CaseOpeningPayload.create(WeaponCaseItem.Pool.ALL, rolled, CASE_KITS,
                CsmcMod.GUNS.keySet(), 1234L, UNIFORM);
        CaseOpeningPayload repeated = CaseOpeningPayload.create(WeaponCaseItem.Pool.ALL, rolled, CASE_KITS,
                CsmcMod.GUNS.keySet(), 1234L, UNIFORM);
        helper.assertValueEqual(repeated, first, "same seed, same strip");
        boolean differs = false;
        for (long seed = 1235; seed < 1245 && !differs; seed++) {
            differs = !CaseOpeningPayload.create(WeaponCaseItem.Pool.ALL, rolled, CASE_KITS, CsmcMod.GUNS.keySet(),
                    seed, UNIFORM).strip().equals(first.strip());
        }
        helper.assertTrue(differs, "other seeds give other strips");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void caseReelRarityFollowsWeights(GameTestHelper helper) {
        List<SkinCatalog.Kit> kits = List.of(
                new SkinCatalog.Kit(910011, "fixture_milspec", "Fixture Mil-Spec", 0, 0, 1, "rare",
                        Set.of("glock"), Set.of("glock"), false),
                new SkinCatalog.Kit(910012, "fixture_restricted", "Fixture Restricted", 0, 0, 1, "mythical",
                        Set.of("glock"), Set.of("glock"), false),
                new SkinCatalog.Kit(910013, "fixture_covert", "Fixture Covert", 0, 0, 1, "ancient",
                        Set.of("glock"), Set.of("glock"), false));
        ToLongFunction<String> weights = rarity -> switch (rarity) {
            case "rare" -> 6L;
            case "mythical" -> 3L;
            default -> 1L;
        };
        int[] counts = new int[SkinCatalog.RARITY_TIERS.size()];
        int fillers = 0;
        for (long seed = 0; seed < 400; seed++) {
            WeaponCaseItem.Rolled rolled = WeaponCaseItem.rollResult(kits, RandomSource.create(seed), weights).orElseThrow();
            CaseOpeningPayload reel = CaseOpeningPayload.create(WeaponCaseItem.Pool.ALL, rolled, kits,
                    CsmcMod.GUNS.keySet(), seed, weights);
            for (int i = 0; i < reel.strip().size(); i++) {
                if (i == reel.landingIndex()) continue;
                counts[reel.strip().get(i).tier()]++;
                fillers++;
            }
        }
        // 400 reels of 49 fillers: three-sigma bands are well under two percentage points.
        assertShare(helper, counts[2], fillers, .6, "rare");
        assertShare(helper, counts[3], fillers, .3, "mythical");
        assertShare(helper, counts[5], fillers, .1, "ancient");
        helper.succeed();
    }

    private static void assertShare(GameTestHelper helper, int count, int total, double expected, String tier) {
        double share = (double) count / total;
        helper.assertTrue(Math.abs(share - expected) < .02, tier + " share " + share + " expected " + expected);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void emptyCatalogKeepsTheCaseAndBuildsNoReel(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            helper.assertTrue(WeaponCaseItem.rollResult(List.of(), RandomSource.create(1), UNIFORM).isEmpty(),
                    "empty catalog rolls nothing");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkinGameplay.WEAPON_CASE));
            InteractionResultHolder<ItemStack> empty = WeaponCaseItem.open(player, InteractionHand.MAIN_HAND,
                    WeaponCaseItem.Pool.ALL, List.of(), RandomSource.create(1), UNIFORM);
            helper.assertValueEqual(empty.getResult(), InteractionResult.FAIL, "empty catalog fails");
            helper.assertValueEqual(countOf(player, SkinGameplay.WEAPON_CASE), 1, "empty catalog keeps the case");
            helper.assertValueEqual(attachments(player).size(), 0, "empty catalog grants nothing");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void craftingTableMenuAppliesSwapsAndRemovesSkins(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            CraftingMenu menu = new CraftingMenu(1, player.getInventory(),
                    ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)));
            assertMenuChain(helper, player, menu, 9, "crafting table");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void inventoryGridAppliesSwapsAndRemovesSkins(GameTestHelper helper) {
        ServerPlayer player = survivalPlayer(helper);
        try {
            assertMenuChain(helper, player, player.inventoryMenu, 4, "inventory grid");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void serverCommandTreeGrantsSkinAttachment(GameTestHelper helper) {
        ServerPlayer player = player(helper, "ak47");
        try {
            ItemStack before = player.getMainHandItem().copy();
            Capture capture = new Capture();
            CommandSourceStack source = player.createCommandSourceStack().withPermission(2).withSource(capture);
            int result;
            try {
                result = helper.getLevel().getServer().getCommands().getDispatcher()
                        .execute("csmc skin 910042 0.25 7", source);
            } catch (CommandSyntaxException exception) {
                throw new GameTestAssertException("/csmc skin is not registered on the server: " + exception.getMessage());
            }
            assertGranted(helper, new Grant("csmc skin 910042 0.25 7", result, attachments(player),
                    ItemStack.matches(player.getMainHandItem(), before), List.copyOf(capture.messages)),
                    "ak47", 910042, .25f, 7, "registered server command");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    /** Apply, swap and remove through a real menu, its recipe manager lookup and its result slot. */
    private static void assertMenuChain(GameTestHelper helper, ServerPlayer player, AbstractContainerMenu menu,
                                        int gridSize, String grid) {
        ItemStack gun = new ItemStack(CsmcMod.GUNS.get("ak47"));
        gun.set(CsmcMod.MAGAZINE, 11);

        // Apply: gun + attachment, placed side by side.
        menu.getSlot(1).set(gun);
        menu.getSlot(2).set(SkinGameplay.skin("ak47", OLD_SKIN));
        ItemStack applied = takeResult(helper, player, menu, grid + " apply");
        helper.assertValueEqual(applied.get(SkinGameplay.SKIN), OLD_SKIN, grid + ": applied skin");
        helper.assertValueEqual(applied.getOrDefault(CsmcMod.MAGAZINE, 0), 11, grid + ": apply keeps magazine");
        assertGrid(helper, menu, gridSize, List.of(), grid + " after apply");

        // Swap: the previous attachment comes back in the gun's slot.
        menu.getSlot(1).set(applied);
        menu.getSlot(gridSize).set(SkinGameplay.skin("ak47", NEW_SKIN));
        ItemStack swapped = takeResult(helper, player, menu, grid + " swap");
        helper.assertValueEqual(swapped.get(SkinGameplay.SKIN), NEW_SKIN, grid + ": swapped skin");
        helper.assertValueEqual(swapped.getOrDefault(CsmcMod.MAGAZINE, 0), 11, grid + ": swap keeps magazine");
        assertGrid(helper, menu, gridSize, List.of(OLD_SKIN), grid + " after swap");
        menu.getSlot(1).set(ItemStack.EMPTY);

        // Remove: a lone skinned gun returns its attachment.
        menu.getSlot(gridSize).set(swapped);
        ItemStack removed = takeResult(helper, player, menu, grid + " removal");
        helper.assertTrue(!removed.has(SkinGameplay.SKIN), grid + ": removal clears the skin");
        helper.assertValueEqual(removed.getOrDefault(CsmcMod.MAGAZINE, 0), 11, grid + ": removal keeps magazine");
        assertGrid(helper, menu, gridSize, List.of(NEW_SKIN), grid + " after removal");
    }

    private static ItemStack takeResult(GameTestHelper helper, ServerPlayer player, AbstractContainerMenu menu,
                                        String context) {
        ItemStack offered = menu.getSlot(0).getItem().copy();
        helper.assertTrue(offered.is(CsmcMod.GUNS.get("ak47")), context + ": result slot offers the gun, got " + offered);
        menu.clicked(0, 0, ClickType.PICKUP, player);
        ItemStack taken = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        helper.assertTrue(ItemStack.matches(taken, offered), context + ": taken result matches the offer");
        return taken;
    }

    /** Grid holds only the expected returned attachments, each for the AK-47; inputs are consumed. */
    private static void assertGrid(GameTestHelper helper, AbstractContainerMenu menu, int gridSize,
                                   List<SkinData> returned, String context) {
        List<SkinData> found = new ArrayList<>();
        for (int slot = 1; slot <= gridSize; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            helper.assertTrue(stack.is(SkinGameplay.WEAPON_SKIN) && stack.getCount() == 1
                    && "ak47".equals(stack.get(SkinGameplay.SKIN_WEAPON)), context + ": unexpected grid item " + stack);
            found.add(stack.get(SkinGameplay.SKIN));
            menu.getSlot(slot).set(ItemStack.EMPTY);
        }
        helper.assertValueEqual(found, returned, context + ": returned attachments");
        helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), context + ": no further result");
    }

    private static void assertCaseAttachment(GameTestHelper helper, ItemStack stack, SkinCatalog.Kit kit, String context) {
        helper.assertTrue(stack.is(SkinGameplay.WEAPON_SKIN) && stack.getCount() == 1, context + ": one attachment");
        helper.assertValueEqual(stack.get(SkinGameplay.SKIN_WEAPON), "glock", context + ": attachment weapon");
        SkinData data = stack.get(SkinGameplay.SKIN);
        helper.assertTrue(data != null && data.paintKit() == kit.paintKit()
                && data.wear() >= kit.wearMin() && data.wear() <= kit.wearMax(), context + ": attachment skin " + data);
    }

    private static int countOf(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void pickerOperatorApplyWritesComponentsAndKeepsMagazine(GameTestHelper helper) {
        ServerPlayer player = player(helper, "ak47");
        try {
            ItemStack gun = player.getMainHandItem();
            helper.assertValueEqual(SkinPicker.apply(player, true, CASE_KITS, 910002, .5f, 999),
                    SkinPicker.Outcome.APPLIED, "operator apply");
            helper.assertTrue(player.getMainHandItem() == gun, "held stack edited in place");
            helper.assertValueEqual(gun.get(SkinGameplay.SKIN), new SkinData(910002, .5f, 999), "skin replaced");
            helper.assertValueEqual(gun.get(SkinGameplay.SKIN_WEAPON), "ak47", "skin weapon written");
            helper.assertValueEqual(gun.getOrDefault(CsmcMod.MAGAZINE, 0), 11, "magazine retained");
            helper.assertValueEqual(gun.get(DataComponents.CUSTOM_NAME), Component.literal("Unrelated custom firearm name"),
                    "custom name retained");
            helper.assertValueEqual(attachments(player).size(), 0, "previous skin not dropped as an attachment");
            // Numeric kits the server catalog does not know follow the command's as-is fallback.
            helper.assertValueEqual(SkinPicker.apply(player, true, List.of(), 123456, 0, 0),
                    SkinPicker.Outcome.APPLIED, "unknown numeric kit without catalog");
            helper.assertValueEqual(gun.get(SkinGameplay.SKIN), new SkinData(123456, 0, 0), "unknown kit applied as-is");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void pickerRejectsNonOperatorIncompatibleOutOfRangeAndNonGun(GameTestHelper helper) {
        ServerPlayer player = player(helper, "glock");
        try {
            ItemStack before = player.getMainHandItem().copy();
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, false, CASE_KITS, 910003, .5f, 1),
                    SkinPicker.Outcome.NOT_OPERATOR, "non-operator");
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, true, CASE_KITS, 910002, .5f, 1),
                    SkinPicker.Outcome.UNSUPPORTED, "rifle kit on glock");
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, true, CASE_KITS, 910003, 1.01f, 1),
                    SkinPicker.Outcome.INVALID_WEAR, "wear above 1");
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, true, CASE_KITS, 910003, -.01f, 1),
                    SkinPicker.Outcome.INVALID_WEAR, "negative wear");
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, true, CASE_KITS, 910003, Float.NaN, 1),
                    SkinPicker.Outcome.INVALID_WEAR, "NaN wear");
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, true, CASE_KITS, 910003, .5f, 1001),
                    SkinPicker.Outcome.INVALID_SEED, "seed above 1000");
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, true, CASE_KITS, 910003, .5f, -1),
                    SkinPicker.Outcome.INVALID_SEED, "negative seed");
            assertPickerRejected(helper, player, before, SkinPicker.apply(player, true, CASE_KITS, -4, .5f, 1),
                    SkinPicker.Outcome.INVALID_ID, "negative kit");

            player.getInventory().setItem(0, new ItemStack(Items.STICK));
            assertPickerRejected(helper, player, new ItemStack(Items.STICK),
                    SkinPicker.apply(player, true, CASE_KITS, 910003, .5f, 1), SkinPicker.Outcome.NOT_GUN, "stick");
            helper.assertTrue(!player.getMainHandItem().has(SkinGameplay.SKIN), "non-gun gains no skin");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void pickerRemoveClearsComponentsAndKeepsMagazine(GameTestHelper helper) {
        ServerPlayer player = player(helper, "ak47");
        try {
            ItemStack gun = player.getMainHandItem();
            gun.set(SkinGameplay.SKIN_WEAPON, "ak47");
            helper.assertValueEqual(SkinPicker.apply(player, true, CASE_KITS, 0, 0, 0),
                    SkinPicker.Outcome.REMOVED, "remove");
            helper.assertTrue(!gun.has(SkinGameplay.SKIN), "skin cleared");
            helper.assertTrue(!gun.has(SkinGameplay.SKIN_WEAPON), "skin weapon cleared");
            helper.assertValueEqual(gun.getOrDefault(CsmcMod.MAGAZINE, 0), 11, "magazine retained");
            helper.assertValueEqual(attachments(player).size(), 0, "removed skin not dropped");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void pickerRateLimitsRepeatedRequests(GameTestHelper helper) {
        ServerPlayer player = player(helper, "ak47");
        try {
            SkinApplyPayload request = new SkinApplyPayload(910002, .5f, 3);
            SkinPicker.Outcome first = SkinPicker.handle(player, request, CASE_KITS);
            helper.assertTrue(first != SkinPicker.Outcome.RATE_LIMITED, "first request is evaluated");
            helper.assertValueEqual(SkinPicker.handle(player, request, CASE_KITS), SkinPicker.Outcome.RATE_LIMITED,
                    "second request in the same tick");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void craftingPickerSkinnedGunMovesTheSkinPair(GameTestHelper helper) {
        ServerPlayer player = player(helper, "ak47");
        try {
            helper.assertValueEqual(SkinPicker.apply(player, true, CASE_KITS, 910002, .5f, 999),
                    SkinPicker.Outcome.APPLIED, "picker apply");
            ItemStack pickerGun = player.getMainHandItem().copy();
            SkinCraftingRecipe recipe = recipe();

            CraftingInput alone = input(2, pickerGun);
            helper.assertTrue(recipe.matches(alone, helper.getLevel()), "picker gun alone matches removal");
            ItemStack removed = recipe.assemble(alone, helper.getLevel().registryAccess());
            helper.assertTrue(!removed.has(SkinGameplay.SKIN), "removal clears skin");
            helper.assertTrue(!removed.has(SkinGameplay.SKIN_WEAPON), "removal clears skin weapon");
            helper.assertValueEqual(removed.getOrDefault(CsmcMod.MAGAZINE, 0), 11, "removal keeps magazine");
            helper.assertTrue(ItemStack.matches(recipe.getRemainingItems(alone).get(0),
                    SkinGameplay.skin("ak47", new SkinData(910002, .5f, 999))), "removal returns the picker skin");

            CraftingInput swap = input(2, pickerGun, SkinGameplay.skin("ak47", NEW_SKIN));
            ItemStack swapped = recipe.assemble(swap, helper.getLevel().registryAccess());
            helper.assertValueEqual(swapped.get(SkinGameplay.SKIN), NEW_SKIN, "swap applies attachment");
            helper.assertValueEqual(swapped.get(SkinGameplay.SKIN_WEAPON), "ak47", "swap keeps the pair");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    private static void assertPickerRejected(GameTestHelper helper, ServerPlayer player, ItemStack before,
                                             SkinPicker.Outcome outcome, SkinPicker.Outcome expected, String reason) {
        helper.assertValueEqual(outcome, expected, reason + ": outcome");
        helper.assertTrue(ItemStack.matches(player.getMainHandItem(), before), reason + ": held item unchanged");
        helper.assertValueEqual(attachments(player).size(), 0, reason + ": no attachment");
    }

    @SuppressWarnings("removal")
    private static ServerPlayer survivalPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        return player;
    }

    private record Grant(String command, int result, List<ItemStack> attachments, boolean gunUnchanged,
                         List<Component> messages) {}

    /** Captures command feedback while the player stays the command entity. */
    private static final class Capture implements CommandSource {
        private final List<Component> messages = new ArrayList<>();

        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    /** Runs the real command tree over explicit kits as an operator holding a fixture gun. */
    private static Grant grant(GameTestHelper helper, List<SkinCatalog.Kit> kits, String weapon, String command) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(SkinCommands.command(kits));
        ServerPlayer player = player(helper, weapon);
        try {
            ItemStack before = player.getMainHandItem().copy();
            Capture capture = new Capture();
            CommandSourceStack source = player.createCommandSourceStack().withPermission(2).withSource(capture);
            int result;
            try {
                result = dispatcher.execute(command, source);
            } catch (CommandSyntaxException exception) {
                throw new GameTestAssertException(command + ": unexpected syntax error " + exception.getMessage());
            }
            return new Grant(command, result, attachments(player),
                    ItemStack.matches(player.getMainHandItem(), before), List.copyOf(capture.messages));
        } finally {
            remove(player);
        }
    }

    private static void assertSyntaxRejected(GameTestHelper helper, List<SkinCatalog.Kit> kits,
                                             String command, String reason) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(SkinCommands.command(kits));
        ServerPlayer player = player(helper, "glock");
        try {
            ItemStack before = player.getMainHandItem().copy();
            CommandSourceStack source = player.createCommandSourceStack().withPermission(2).withSource(new Capture());
            boolean rejected = false;
            try {
                dispatcher.execute(command, source);
            } catch (CommandSyntaxException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, reason + ": argument bounds reject command");
            helper.assertValueEqual(attachments(player).size(), 0, reason + ": no attachment");
            helper.assertTrue(ItemStack.matches(player.getMainHandItem(), before), reason + ": held gun unchanged");
        } finally {
            remove(player);
        }
    }

    private static void assertGranted(GameTestHelper helper, Grant grant, String weapon, int paintKit,
                                      float wear, Integer seed, String reason) {
        String context = reason + " (" + grant.command() + ")";
        helper.assertValueEqual(grant.result(), 1, context + ": command result");
        helper.assertTrue(grant.gunUnchanged(), context + ": held gun unchanged");
        helper.assertValueEqual(grant.attachments().size(), 1, context + ": exactly one attachment");
        ItemStack attachment = grant.attachments().get(0);
        helper.assertValueEqual(attachment.getCount(), 1, context + ": attachment count");
        helper.assertValueEqual(attachment.get(SkinGameplay.SKIN_WEAPON), weapon, context + ": attachment weapon");
        SkinData data = attachment.get(SkinGameplay.SKIN);
        helper.assertTrue(data != null, context + ": attachment carries skin data");
        helper.assertValueEqual(data.paintKit(), paintKit, context + ": paint kit");
        helper.assertValueEqual(data.wear(), wear, context + ": wear");
        helper.assertTrue(data.seed() >= 0 && data.seed() <= 1000, context + ": seed bounds");
        if (seed != null) helper.assertValueEqual(data.seed(), seed, context + ": explicit seed");
        helper.assertTrue(hasKey(grant.messages(), "commands.csmc.skin.success"), context + ": success feedback");
    }

    private static void assertRejectedGrant(GameTestHelper helper, Grant grant, String key, String reason) {
        String context = reason + " (" + grant.command() + ")";
        helper.assertValueEqual(grant.result(), 0, context + ": command result");
        helper.assertTrue(grant.gunUnchanged(), context + ": held gun unchanged");
        helper.assertValueEqual(grant.attachments().size(), 0, context + ": no attachment");
        helper.assertTrue(hasKey(grant.messages(), key), context + ": failure feedback " + key);
    }

    private static boolean hasKey(List<Component> messages, String key) {
        for (Component message : messages) {
            if (hasKey(message, key)) return true;
        }
        return false;
    }

    private static boolean hasKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key)) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (hasKey(sibling, key)) return true;
        }
        return false;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, String weapon) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().setItem(0, gun(weapon, OLD_SKIN));
        player.getInventory().selected = 0;
        return player;
    }

    private static List<ItemStack> attachments(ServerPlayer player) {
        List<ItemStack> found = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(SkinGameplay.WEAPON_SKIN)) found.add(stack.copy());
        }
        return found;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }

    private static void assertCraft(GameTestHelper helper, int width, int originalGunSlot,
                                    String weapon, SkinData previous, SkinData next) {
        String context = width + "x" + width + " " + weapon + " gun slot " + originalGunSlot;
        ItemStack gun = gun(weapon, previous);
        List<ItemStack> grid = emptyGrid(width);
        grid.set(originalGunSlot, gun);
        if (next != null) {
            ItemStack attachment = SkinGameplay.skin(weapon, next);
            attachment.setCount(3);
            grid.set((originalGunSlot + width * width - 1) % (width * width), attachment);
        }
        // CraftingInput.of trims empty borders, including a lone gun down to 1x1.
        CraftingInput input = CraftingInput.of(width, width, grid);
        List<ItemStack> before = snapshot(input);
        int gunSlot = input.items().indexOf(gun);
        helper.assertTrue(gunSlot >= 0, context + ": normalized input retains gun");
        SkinCraftingRecipe recipe = recipe();
        helper.assertTrue(recipe.matches(input, helper.getLevel()), context + ": recipe matches");
        assertUnchanged(helper, input, before, context + " after matches");

        ItemStack result = recipe.assemble(input, helper.getLevel().registryAccess());
        ItemStack expected = gun.copyWithCount(1);
        // The skin and its weapon tag travel as a pair on the gun, as the picker writes them.
        if (next == null) {
            expected.remove(SkinGameplay.SKIN);
            expected.remove(SkinGameplay.SKIN_WEAPON);
        } else {
            expected.set(SkinGameplay.SKIN, next);
            expected.set(SkinGameplay.SKIN_WEAPON, weapon);
        }
        helper.assertTrue(result != gun, context + ": result is a distinct stack");
        helper.assertTrue(ItemStack.matches(result, expected), context + ": only skin and output count change");
        helper.assertValueEqual(result.getCount(), 1, context + ": output count");
        helper.assertValueEqual(result.getOrDefault(CsmcMod.MAGAZINE, 0), 11, context + ": magazine retained");
        helper.assertValueEqual(result.get(DataComponents.CUSTOM_NAME), gun.get(DataComponents.CUSTOM_NAME),
                context + ": unrelated custom name retained");
        assertUnchanged(helper, input, before, context + " after assemble");

        List<ItemStack> remaining = recipe.getRemainingItems(input);
        helper.assertValueEqual(remaining.size(), input.size(), context + ": remainder slot count");
        for (int slot = 0; slot < input.size(); slot++) {
            if (previous != null && slot == gunSlot) {
                helper.assertTrue(ItemStack.matches(remaining.get(slot), SkinGameplay.skin(weapon, previous)),
                        context + ": exactly one old attachment returns in gun input slot");
            } else {
                helper.assertTrue(remaining.get(slot).isEmpty(), context + ": no other remainder at " + slot);
            }
        }
        assertUnchanged(helper, input, before, context + " after remainders");
        result.set(CsmcMod.MAGAZINE, 0);
        result.set(DataComponents.CUSTOM_NAME, Component.literal("Changed output only"));
        assertUnchanged(helper, input, before, context + " after editing output");
    }

    private static void assertRejected(GameTestHelper helper, CraftingInput input, String reason) {
        SkinCraftingRecipe recipe = recipe();
        List<ItemStack> before = snapshot(input);
        helper.assertTrue(!recipe.matches(input, helper.getLevel()), reason + ": no match");
        helper.assertTrue(recipe.assemble(input, helper.getLevel().registryAccess()).isEmpty(), reason + ": no output");
        List<ItemStack> remaining = recipe.getRemainingItems(input);
        helper.assertValueEqual(remaining.size(), input.size(), reason + ": remainder size");
        for (ItemStack stack : remaining) {
            helper.assertTrue(stack.isEmpty(), reason + ": no attachment can be recovered from invalid input");
        }
        assertUnchanged(helper, input, before, reason);
    }

    private static SkinCraftingRecipe recipe() {
        return new SkinCraftingRecipe(CraftingBookCategory.MISC);
    }

    private static ItemStack gun(String weapon, SkinData skin) {
        ItemStack gun = new ItemStack(CsmcMod.SKINNABLE.get(weapon), 2);
        gun.set(CsmcMod.MAGAZINE, 11);
        gun.set(DataComponents.CUSTOM_NAME, Component.literal("Unrelated custom firearm name"));
        if (skin != null) {
            gun.set(SkinGameplay.SKIN, skin);
        }
        return gun;
    }

    private static List<ItemStack> emptyGrid(int width) {
        return new ArrayList<>(Collections.nCopies(width * width, ItemStack.EMPTY));
    }

    private static CraftingInput input(int width, ItemStack... occupied) {
        List<ItemStack> grid = emptyGrid(width);
        for (int index = 0; index < occupied.length; index++) {
            int slot = index == 0 ? 0 : index == 1 ? width * width - 1 : index - 1;
            grid.set(slot, occupied[index]);
        }
        return CraftingInput.of(width, width, grid);
    }

    private static List<ItemStack> snapshot(CraftingInput input) {
        return input.items().stream().map(ItemStack::copy).toList();
    }

    private static void assertUnchanged(GameTestHelper helper, CraftingInput input,
                                        List<ItemStack> before, String context) {
        for (int slot = 0; slot < input.size(); slot++) {
            helper.assertTrue(ItemStack.matches(input.getItem(slot), before.get(slot)),
                    context + ": input slot " + slot + " remains unchanged");
        }
    }
}

