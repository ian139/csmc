package dev.csmc.skin.gameplay;

import dev.csmc.skin.SkinCatalog;
import dev.csmc.skin.SkinData;
import dev.csmc.skin.SkinNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure kit selection for the skin picker: availability, name search and rarity tiers. */
public final class SkinPickerFilter {
    /** Every bit of {@link SkinCatalog#RARITY_TIERS}. */
    public static final int ALL_TIERS = (1 << SkinCatalog.RARITY_TIERS.size()) - 1;
    /** Gold ★ ({@code immortal}): every knife finish, whatever its kit rarity. */
    public static final int KNIFE_TIER = SkinCatalog.RARITY_TIERS.size() - 1;
    private static final Comparator<Labeled> BY_LABEL = Comparator.comparing(Labeled::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(labeled -> labeled.kit().paintKit());

    private record Labeled(String label, SkinCatalog.Kit kit) {}

    private SkinPickerFilter() {}

    /** Index into {@link SkinCatalog#RARITY_TIERS}; unknown rarities count as {@code rare}, as cases do. */
    public static int tier(String rarity) {
        return WeaponCaseItem.rarityTier(rarity);
    }

    public static int bit(int tier) {
        return 1 << tier;
    }

    /** Tier of {@code kit} on a knife ({@code knife}) or gun, matching {@link WeaponCaseItem#pairTier}. */
    public static int pairTier(SkinCatalog.Kit kit, boolean knife) {
        return knife ? KNIFE_TIER : tier(kit.rarity());
    }

    /**
     * Skin texture variants the picker holds on screen: distinct skins among {@code tiles[from, to)}, plus the large
     * {@code preview} and the held gun's {@code applied} skin when they differ from those. Null and paint kit 0 hold
     * no variant. Pass the result to {@link CaseScroll#prefetchBudget} so prefetching never evicts any of them.
     */
    public static int distinctVariants(SkinData[] tiles, int from, int to, SkinData preview, SkinData applied) {
        int distinct = 0;
        for (int i = from; i < to; i++) {
            if (holdsVariant(tiles[i]) && !seen(tiles, from, i, tiles[i])) distinct++;
        }
        if (holdsVariant(preview) && !seen(tiles, from, to, preview)) distinct++;
        if (holdsVariant(applied) && !applied.equals(preview) && !seen(tiles, from, to, applied)) distinct++;
        return distinct;
    }

    private static boolean holdsVariant(SkinData skin) {
        return skin != null && skin.paintKit() != 0;
    }

    private static boolean seen(SkinData[] tiles, int from, int to, SkinData skin) {
        for (int i = from; i < to; i++) {
            if (skin.equals(tiles[i])) return true;
        }
        return false;
    }

    /**
     * Kits available for {@code weapon} whose {@link SkinNames#displayName display label} or internal name contains
     * the trimmed {@code query} (case-insensitive) and whose {@link #pairTier pair tier} bit is in {@code tierMask},
     * sorted by display label. {@code knife} says whether {@code weapon} is a knife.
     */
    public static List<SkinCatalog.Kit> filter(List<SkinCatalog.Kit> kits, String weapon, boolean knife, String query,
            int tierMask) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Labeled> matches = new ArrayList<>();
        for (SkinCatalog.Kit kit : kits) {
            if (!kit.availableFor(weapon) || (tierMask & bit(pairTier(kit, knife))) == 0) continue;
            String label = SkinNames.displayName(kit);
            if (!needle.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(needle)
                    && !kit.name().toLowerCase(Locale.ROOT).contains(needle)) continue;
            matches.add(new Labeled(label, kit));
        }
        matches.sort(BY_LABEL);
        List<SkinCatalog.Kit> result = new ArrayList<>(matches.size());
        for (Labeled labeled : matches) result.add(labeled.kit());
        return result;
    }
}
