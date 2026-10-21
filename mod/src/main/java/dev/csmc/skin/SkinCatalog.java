package dev.csmc.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.csmc.CsmcMod;
import dev.csmc.data.CachePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Server and client read their own external index; no Valve data is synchronized or bundled. */
public final class SkinCatalog {
    /** Canonical weapon-skin rarity tiers, most to least common. */
    public static final List<String> RARITY_TIERS =
            List.of("common", "uncommon", "rare", "mythical", "legendary", "ancient", "immortal");
    /** Rarity RGB colours indexed like {@link #RARITY_TIERS}: grey, light blue, blue, purple, pink, red, gold. */
    private static final int[] TIER_COLORS = {0xB0C3D9, 0x5E98D9, 0x4B69FF, 0x8847FF, 0xD32CE6, 0xEB4B4B, 0xE4AE39};

    /** RGB colour for a tier index, clamped into range. */
    public static int tierColor(int tier) {
        return TIER_COLORS[Math.max(0, Math.min(TIER_COLORS.length - 1, tier))];
    }
    /** Upper bound for one tier's weight; keeps sums over all tiers well inside a long. */
    public static final long MAX_RARITY_WEIGHT = 1_000_000_000L;
    /** Authored fallback for tiers the local index omits (each tier five times rarer). */
    private static final Map<String, Long> DEFAULT_RARITY_WEIGHTS = Map.of(
            "common", 10_000_000L, "uncommon", 2_000_000L, "rare", 400_000L, "mythical", 80_000L,
            "legendary", 16_000L, "ancient", 3_200L, "immortal", 640L);

    private static List<Kit> kits = List.of();
    private static Map<Integer, Kit> byId = Map.of();
    private static Kit[] renderIndex = new Kit[0];
    private static Map<String, Long> rarityWeights = DEFAULT_RARITY_WEIGHTS;
    private static Path loadedRoot;
    private static long nextCheck;
    private static long loadedStamp = Long.MIN_VALUE;

    private SkinCatalog() { }

    public record Kit(int paintKit, String name, String displayName, int style, float wearMin, float wearMax,
                      String rarity, Set<String> weapons, Set<String> availableWeapons, boolean legacyModel) {
        public Kit {
            weapons = Set.copyOf(weapons);
            availableWeapons = Set.copyOf(availableWeapons);
            if (paintKit <= 0 || !Float.isFinite(wearMin) || !Float.isFinite(wearMax)
                    || wearMin < 0 || wearMax > 1 || wearMax < wearMin) {
                throw new IllegalArgumentException("Invalid paint kit id or wear range");
            }
            if (!weapons.containsAll(availableWeapons)) throw new IllegalArgumentException("Unavailable weapon association");
        }
        public boolean supports(String weapon) { return weapons.contains(weapon); }
        public boolean availableFor(String weapon) { return availableWeapons.contains(weapon); }
    }

    public static synchronized List<Kit> kits() { refresh(); return kits; }
    public static synchronized Optional<Kit> byId(int id) { refresh(); return Optional.ofNullable(byId.get(id)); }
    public static synchronized void reload() { loadedRoot = null; nextCheck = 0; refresh(); }

    /**
     * Drop weight for a rarity tier, in (0, {@link #MAX_RARITY_WEIGHT}]: the local index's items_game weight when
     * valid, otherwise the authored default. Unknown rarity names use the common weight.
     */
    public static synchronized long rarityWeight(String rarity) {
        refresh();
        return rarityWeight(rarityWeights, rarity);
    }

    /** Pure lookup over weights produced by {@link #parseRarityWeights}. */
    public static long rarityWeight(Map<String, Long> weights, String rarity) {
        Long weight = rarity == null ? null : weights.get(rarity);
        return weight != null ? weight : weights.get("common");
    }

    /** Render-thread lookup: never checks the filesystem. Reload this snapshot during resource reload. */
    public static synchronized Kit renderKit(int id) {
        return id >= 0 && id < renderIndex.length ? renderIndex[id] : null;
    }

    private static void refresh() {
        Path root = CachePaths.root().toAbsolutePath().normalize();
        long now = System.nanoTime();
        if (root.equals(loadedRoot) && now < nextCheck) return;
        nextCheck = now + 2_000_000_000L;
        Path index = root.resolve("skins/index.json");
        try {
            long stamp = Files.isRegularFile(index) ? Files.getLastModifiedTime(index).toMillis() : -1;
            if (root.equals(loadedRoot) && loadedStamp == stamp) return;
            loadedRoot = root;
            loadedStamp = stamp;
            String text = stamp < 0 ? null : Files.readString(index);
            List<Kit> parsed = text == null ? List.of() : parse(text);
            Map<Integer, Kit> ids = new LinkedHashMap<>();
            for (Kit kit : parsed) ids.put(kit.paintKit(), kit);
            kits = List.copyOf(ids.values());
            byId = Map.copyOf(ids);
            int max = parsed.stream().mapToInt(Kit::paintKit).max().orElse(0);
            renderIndex = new Kit[Math.min(65535, max) + 1];
            for (Kit kit : parsed) if (kit.paintKit() < renderIndex.length) renderIndex[kit.paintKit()] = kit;
            rarityWeights = text == null ? DEFAULT_RARITY_WEIGHTS : parseRarityWeights(text);
        } catch (IOException | RuntimeException e) {
            kits = List.of();
            byId = Map.of();
            renderIndex = new Kit[0];
            rarityWeights = DEFAULT_RARITY_WEIGHTS;
            CsmcMod.LOGGER.warn("[csmc skins] Cannot read {}; skin names/cases unavailable", index, e);
        }
    }

    /** Pure parser for a self-authored fixture or a local exported index. */
    public static List<Kit> parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (root.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unsupported skin index schema");
        List<Kit> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("kits")) {
            JsonObject kit = element.getAsJsonObject();
            result.add(new Kit(kit.get("paint_kit").getAsInt(), kit.get("name").getAsString(),
                    kit.get("display_name").getAsString(), kit.get("style").getAsInt(),
                    kit.get("wear_min").getAsFloat(), kit.get("wear_max").getAsFloat(),
                    kit.has("rarity") ? kit.get("rarity").getAsString() : "rare",
                    strings(kit, "weapons"), strings(kit, "available_weapons"),
                    kit.has("legacy_model") && kit.get("legacy_model").getAsBoolean()));
        }
        return List.copyOf(result);
    }

    /**
     * Pure parser for the index root's {@code rarity_weights}: one weight per canonical tier. A tier whose value is
     * absent, non-integral, non-positive, or above {@link #MAX_RARITY_WEIGHT} keeps its authored default; names
     * outside {@link #RARITY_TIERS} are ignored.
     */
    public static Map<String, Long> parseRarityWeights(String text) {
        JsonElement element = JsonParser.parseString(text).getAsJsonObject().get("rarity_weights");
        JsonObject source = element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        Map<String, Long> result = new LinkedHashMap<>();
        for (String tier : RARITY_TIERS) {
            long weight = DEFAULT_RARITY_WEIGHTS.get(tier);
            JsonElement value = source.get(tier);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                double raw = value.getAsDouble();
                if (raw == Math.rint(raw) && raw > 0 && raw <= MAX_RARITY_WEIGHT) weight = (long) raw;
            }
            result.put(tier, weight);
        }
        return Map.copyOf(result);
    }

    private static Set<String> strings(JsonObject object, String key) {
        Set<String> result = new LinkedHashSet<>();
        if (object.has(key)) for (JsonElement entry : object.getAsJsonArray(key)) result.add(entry.getAsString());
        return result;
    }
}
