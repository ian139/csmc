package dev.csmc.skin;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkinCatalogTest {
    // Self-authored fixtures only: these weights are deliberately not Valve's.
    private static final String KIT = "{\"paint_kit\":910001,\"name\":\"fixture_kit\",\"display_name\":\"Fixture Kit\","
            + "\"style\":1,\"wear_min\":0.1,\"wear_max\":0.9,\"rarity\":\"uncommon\",\"weapons\":[\"glock\"],"
            + "\"available_weapons\":[\"glock\"],\"legacy_model\":false}";

    private static String index(String rarityWeights) {
        return "{\"schema\":1,\"kits\":[" + KIT + "]" + (rarityWeights == null ? "" : ",\"rarity_weights\":" + rarityWeights) + "}";
    }

    @Test public void loadedWeightsReplaceDefaultsPerTier() {
        Map<String, Long> weights = SkinCatalog.parseRarityWeights(index(
                "{\"common\":7000,\"uncommon\":700,\"rare\":70,\"mythical\":7,\"legendary\":3,\"ancient\":2,\"immortal\":1}"));
        assertEquals(7000, SkinCatalog.rarityWeight(weights, "common"));
        assertEquals(700, SkinCatalog.rarityWeight(weights, "uncommon"));
        assertEquals(70, SkinCatalog.rarityWeight(weights, "rare"));
        assertEquals(7, SkinCatalog.rarityWeight(weights, "mythical"));
        assertEquals(3, SkinCatalog.rarityWeight(weights, "legendary"));
        assertEquals(2, SkinCatalog.rarityWeight(weights, "ancient"));
        assertEquals(1, SkinCatalog.rarityWeight(weights, "immortal"));
    }

    @Test public void missingTiersAndMissingRootUseAuthoredDefaults() {
        Map<String, Long> partial = SkinCatalog.parseRarityWeights(index("{\"common\":5000,\"rare\":300}"));
        assertEquals(5000, SkinCatalog.rarityWeight(partial, "common"));
        assertEquals(2_000_000, SkinCatalog.rarityWeight(partial, "uncommon"));
        assertEquals(300, SkinCatalog.rarityWeight(partial, "rare"));
        assertEquals(16_000, SkinCatalog.rarityWeight(partial, "legendary"));
        assertEquals(640, SkinCatalog.rarityWeight(partial, "immortal"));

        Map<String, Long> absent = SkinCatalog.parseRarityWeights(index(null));
        long[] expected = {10_000_000, 2_000_000, 400_000, 80_000, 16_000, 3_200, 640};
        for (int tier = 0; tier < expected.length; tier++) {
            assertEquals(expected[tier], SkinCatalog.rarityWeight(absent, SkinCatalog.RARITY_TIERS.get(tier)));
        }
        assertEquals(absent, SkinCatalog.parseRarityWeights(index("[1,2]")));
    }

    @Test public void malformedWeightsFallBackWithinBounds() {
        Map<String, Long> weights = SkinCatalog.parseRarityWeights(index("{\"common\":0,\"uncommon\":-5,"
                + "\"rare\":1000000001,\"mythical\":\"80\",\"legendary\":2.5,\"ancient\":null,\"immortal\":1000000000,"
                + "\"unusual\":4}"));
        assertEquals(10_000_000, SkinCatalog.rarityWeight(weights, "common"));
        assertEquals(2_000_000, SkinCatalog.rarityWeight(weights, "uncommon"));
        assertEquals(400_000, SkinCatalog.rarityWeight(weights, "rare"));
        assertEquals(80_000, SkinCatalog.rarityWeight(weights, "mythical"));
        assertEquals(16_000, SkinCatalog.rarityWeight(weights, "legendary"));
        assertEquals(3_200, SkinCatalog.rarityWeight(weights, "ancient"));
        assertEquals(SkinCatalog.MAX_RARITY_WEIGHT, SkinCatalog.rarityWeight(weights, "immortal"));
        assertEquals(SkinCatalog.RARITY_TIERS.size(), weights.size());
        for (long weight : weights.values()) assertTrue(weight > 0 && weight <= SkinCatalog.MAX_RARITY_WEIGHT);
    }

    @Test public void unknownRarityUsesCommonWeight() {
        Map<String, Long> weights = SkinCatalog.parseRarityWeights(index("{\"common\":4321}"));
        assertEquals(4321, SkinCatalog.rarityWeight(weights, "unusual"));
        assertEquals(4321, SkinCatalog.rarityWeight(weights, "Rare"));
        assertEquals(4321, SkinCatalog.rarityWeight(weights, null));
    }
}
