package dev.csmc.skin.gameplay;

import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkinPickerFilterTest {
    private static SkinCatalog.Kit kit(int id, String name, String display, String rarity, Set<String> available) {
        return new SkinCatalog.Kit(id, name, display, 0, 0, 1, rarity, Set.of("ak47", "glock", "knife_karambit"), available, false);
    }

    private static final SkinCatalog.Kit REDLINE = kit(1, "cu_ak47_redline", "Redline", "mythical", Set.of("ak47"));
    private static final SkinCatalog.Kit ASIIMOV = kit(2, "cu_asiimov", "Asiimov", "ancient", Set.of("ak47", "glock"));
    private static final SkinCatalog.Kit SAFARI = kit(3, "sp_safari", "Safari Mesh", "common", Set.of("ak47"));
    private static final SkinCatalog.Kit GLOCK_ONLY = kit(4, "gl_fade", "Fade", "legendary", Set.of("glock"));
    private static final SkinCatalog.Kit UNAVAILABLE = kit(5, "aa_unreleased", "Unreleased", "rare", Set.of());
    private static final SkinCatalog.Kit DOPPLER_P2 =
            kit(9, "am_doppler_phase2", "Doppler", "mythical", Set.of("ak47", "knife_karambit"));
    private static final SkinCatalog.Kit DOPPLER_SAPPHIRE =
            kit(7, "am_sapphire_marbleized_b", "Doppler", "legendary", Set.of("knife_karambit"));
    private static final SkinCatalog.Kit GAMMA_P4 =
            kit(8, "am_gamma_doppler_phase4_glock", "Gamma Doppler", "ancient", Set.of("glock"));
    private static final List<SkinCatalog.Kit> KITS =
            List.of(REDLINE, ASIIMOV, SAFARI, GLOCK_ONLY, UNAVAILABLE, DOPPLER_P2, DOPPLER_SAPPHIRE, GAMMA_P4);

    @Test public void onlyKitsAvailableForTheWeaponSortedByDisplayName() {
        assertEquals(List.of(ASIIMOV, DOPPLER_P2, REDLINE, SAFARI),
                SkinPickerFilter.filter(KITS, "ak47", false, "", SkinPickerFilter.ALL_TIERS));
        assertEquals(List.of(ASIIMOV, GLOCK_ONLY, GAMMA_P4),
                SkinPickerFilter.filter(KITS, "glock", false, "", SkinPickerFilter.ALL_TIERS));
        assertEquals(List.of(), SkinPickerFilter.filter(KITS, "awp", false, "", SkinPickerFilter.ALL_TIERS));
    }

    @Test public void searchMatchesDisplayOrInternalNameCaseInsensitivelyAndTrimmed() {
        assertEquals(List.of(REDLINE), SkinPickerFilter.filter(KITS, "ak47", false, "  rEdL ", SkinPickerFilter.ALL_TIERS));
        assertEquals(List.of(SAFARI), SkinPickerFilter.filter(KITS, "ak47", false, "mesh", SkinPickerFilter.ALL_TIERS));
        assertEquals(List.of(SAFARI), SkinPickerFilter.filter(KITS, "ak47", false, "sp_saf", SkinPickerFilter.ALL_TIERS));
        assertEquals(List.of(), SkinPickerFilter.filter(KITS, "ak47", false, "fade", SkinPickerFilter.ALL_TIERS));
    }

    @Test public void searchAndSortUsePhaseAwareLabels() {
        int all = SkinPickerFilter.ALL_TIERS;
        assertEquals(List.of(DOPPLER_P2), SkinPickerFilter.filter(KITS, "knife_karambit", true, "phase 2", all));
        assertEquals(List.of(DOPPLER_SAPPHIRE), SkinPickerFilter.filter(KITS, "knife_karambit", true, "doppler (sapph", all));
        assertEquals(List.of(GAMMA_P4), SkinPickerFilter.filter(KITS, "glock", false, "gamma doppler (phase 4", all));
        // By label "Doppler (Phase 2)" precedes "Doppler (Sapphire)"; raw "Doppler" would tie and order by paint kit 7, 9.
        assertEquals(List.of(DOPPLER_P2, DOPPLER_SAPPHIRE), SkinPickerFilter.filter(KITS, "knife_karambit", true, "", all));
    }

    @Test public void rarityMaskSelectsTiers() {
        int mask = SkinPickerFilter.bit(SkinPickerFilter.tier("ancient")) | SkinPickerFilter.bit(SkinPickerFilter.tier("common"));
        assertEquals(List.of(ASIIMOV, SAFARI), SkinPickerFilter.filter(KITS, "ak47", false, "", mask));
        assertEquals(List.of(), SkinPickerFilter.filter(KITS, "ak47", false, "", 0));
        assertEquals(List.of(ASIIMOV), SkinPickerFilter.filter(KITS, "ak47", false, "asi", mask));
    }

    @Test public void knifeFinishesAreAllGoldWhateverTheKitRarity() {
        int gold = SkinPickerFilter.bit(SkinPickerFilter.tier("immortal"));
        assertEquals(SkinPickerFilter.tier("immortal"), SkinPickerFilter.KNIFE_TIER);
        assertEquals(List.of(DOPPLER_P2, DOPPLER_SAPPHIRE), SkinPickerFilter.filter(KITS, "knife_karambit", true, "", gold));
        int kitRarities = SkinPickerFilter.bit(SkinPickerFilter.tier("mythical")) | SkinPickerFilter.bit(SkinPickerFilter.tier("legendary"));
        assertEquals(List.of(), SkinPickerFilter.filter(KITS, "knife_karambit", true, "", kitRarities));
        // The same kit on a gun keeps its own rarity.
        assertEquals(List.of(), SkinPickerFilter.filter(KITS, "ak47", false, "", gold));
        assertEquals(List.of(DOPPLER_P2), SkinPickerFilter.filter(KITS, "ak47", false, "doppler", kitRarities));
        assertEquals(SkinPickerFilter.KNIFE_TIER, SkinPickerFilter.pairTier(SAFARI, true));
        assertEquals(SkinPickerFilter.tier("common"), SkinPickerFilter.pairTier(SAFARI, false));
    }

    @Test public void tiersFollowTheCanonicalOrder() {
        for (int i = 0; i < SkinCatalog.RARITY_TIERS.size(); i++) {
            assertEquals(i, SkinPickerFilter.tier(SkinCatalog.RARITY_TIERS.get(i)));
        }
        assertEquals(SkinCatalog.RARITY_TIERS.size(), Integer.bitCount(SkinPickerFilter.ALL_TIERS));
    }

    private static final SkinData A = new SkinData(1, .1f, 0);
    private static final SkinData B = new SkinData(2, .1f, 0);
    private static final SkinData C = new SkinData(3, .1f, 0);
    private static final SkinData A_WORN = new SkinData(1, .9f, 7);
    private static final SkinData NONE = new SkinData(0, 0, 0);

    @Test public void reservedVariantsCountDistinctTilesPreviewAndAppliedSkin() {
        SkinData[] tiles = {null, A, B, C, A};
        // Tile 0 ("no skin") is skipped by the range; the repeated A counts once.
        assertEquals(3, SkinPickerFilter.distinctVariants(tiles, 1, 5, null, null));
        assertEquals(3, SkinPickerFilter.distinctVariants(tiles, 1, 5, B, A));
        assertEquals(4, SkinPickerFilter.distinctVariants(tiles, 1, 5, A_WORN, null));
        assertEquals(4, SkinPickerFilter.distinctVariants(tiles, 1, 5, null, A_WORN));
        assertEquals(4, SkinPickerFilter.distinctVariants(tiles, 1, 5, A_WORN, A_WORN));
        assertEquals(5, SkinPickerFilter.distinctVariants(tiles, 1, 5, A_WORN, new SkinData(9, .5f, 1)));
        assertEquals(3, SkinPickerFilter.distinctVariants(tiles, 1, 5, NONE, NONE));
        assertEquals(0, SkinPickerFilter.distinctVariants(tiles, 2, 2, null, NONE));
    }

    @Test public void fullGridWithPreviewAndHeldSkinNeverOverflowsThePool() {
        int capacity = 32;
        SkinData[] tiles = new SkinData[29];
        for (int i = 1; i < tiles.length; i++) tiles[i] = new SkinData(i, 0, 0);
        SkinData preview = new SkinData(100, .3f, 4);
        SkinData held = new SkinData(200, .2f, 5);
        int reserved = SkinPickerFilter.distinctVariants(tiles, 1, tiles.length, preview, held);
        assertEquals(30, reserved);
        int budget = CaseScroll.prefetchBudget(reserved, capacity, 7);
        assertEquals(2, budget);
        assertTrue(reserved + budget <= capacity);
    }
}
