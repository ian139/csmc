package dev.csmc.melee;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authored per-knife melee tuning from the server data file {@code data/csmc/knives/<id>.json}, reloaded with
 * {@code /reload}. These are Minecraft gameplay adaptations, not CS2 data (CS2's knife vdata is identical across
 * types). Damage is in CS units (100 HP player), converted to health like bullets; ranges are CS units (16 per
 * block); cooldowns are seconds. A missing or malformed file logs an error and that knife uses {@link #BASE}.
 */
public record KnifeStats(double slashDamage, double slashFollowupDamage, double stabDamage,
                         double slashBackstabDamage, double stabBackstabDamage,
                         double slashRange, double stabRange,
                         double slashCooldown, double slashFollowupCooldown, double stabCooldown,
                         double armorRatio) {
    private static final Logger LOGGER = LoggerFactory.getLogger("csmc");
    public static final String DIRECTORY = "knives";
    /** CS:GO knife baseline; the fallback for a knife whose data file is missing or invalid. */
    public static final KnifeStats BASE = new KnifeStats(40, 25, 65, 90, 180, 48, 32, 0.5, 0.4, 1.0, 1.7);

    private static volatile Map<String, KnifeStats> all = Map.of();

    public KnifeStats {
        double[] values = {slashDamage, slashFollowupDamage, stabDamage, slashBackstabDamage, stabBackstabDamage,
                slashRange, stabRange, slashCooldown, slashFollowupCooldown, stabCooldown, armorRatio};
        for (double value : values) {
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("knife stats must be positive: " + value);
        }
    }

    /** Loaded stats for knife {@code id}, or {@link #BASE} before the first data load or for an unknown id. */
    public static KnifeStats get(String id) {
        return all.getOrDefault(id, BASE);
    }

    /** Snapshot of the loaded stats, in knife id order. Empty before the first data load. */
    public static Map<String, KnifeStats> all() {
        return all;
    }

    /** Registers the SERVER_DATA reload listener for {@code ids}. Call once during mod initialization. */
    public static void register(List<String> ids) {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath("csmc", DIRECTORY);
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                all = resolve(ids, id -> read(manager, id), (id, error) ->
                        LOGGER.error("[csmc] Knife stats for {} are invalid ({}); using the base knife stats", id, error));
            }
        });
    }

    private static JsonObject read(ResourceManager manager, String id) {
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath("csmc", DIRECTORY + "/" + id + ".json");
        Resource resource = manager.getResource(path).orElseThrow(() -> new IllegalArgumentException("missing " + path));
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("unreadable " + path, e);
        }
    }

    /**
     * Parses every id with {@code reader}; an id whose read or parse throws is reported to {@code error} and falls back
     * to {@link #BASE}. Pure, so it is unit-testable without a resource manager.
     */
    public static Map<String, KnifeStats> resolve(List<String> ids, Function<String, JsonObject> reader,
                                                 BiConsumer<String, String> error) {
        Map<String, KnifeStats> stats = new LinkedHashMap<>();
        for (String id : ids) {
            KnifeStats parsed;
            try {
                parsed = parse(reader.apply(id));
            } catch (RuntimeException e) {
                error.accept(id, String.valueOf(e.getMessage()));
                parsed = BASE;
            }
            stats.put(id, parsed);
        }
        return Collections.unmodifiableMap(stats);
    }

    public static KnifeStats parse(JsonObject json) {
        return new KnifeStats(num(json, "slash_damage"), num(json, "slash_followup_damage"), num(json, "stab_damage"),
                num(json, "slash_backstab_damage"), num(json, "stab_backstab_damage"),
                num(json, "slash_range"), num(json, "stab_range"),
                num(json, "slash_cooldown"), num(json, "slash_followup_cooldown"), num(json, "stab_cooldown"),
                num(json, "armor_ratio"));
    }

    private static double num(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing or non-numeric " + key);
        }
        return json.get(key).getAsDouble();
    }
}
