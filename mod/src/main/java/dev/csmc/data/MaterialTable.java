package dev.csmc.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import dev.csmc.CsmcMod;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Block state → CS2 surface table, loaded from {@code data/csmc/ballistics/surfaces.json} (highest-priority data
 * pack wins; the file is replaced as a whole, not merged).
 *
 * <p>Each resolved {@link Material} carries:
 * <ul>
 *   <li>{@code name}: the CS2 surface name ({@code surfacePropertyName}) the block maps to.</li>
 *   <li>{@code distanceModifier} / {@code damageModifier}: CS2
 *       {@code bulletPenetrationDistanceModifier} / {@code bulletPenetrationDamageModifier}, in [0, 1].</li>
 *   <li>{@code breakMode}: {@code SHATTER} destroys the block on hit; {@code CUMULATIVE} accumulates
 *       CS bullet damage per position until {@code breakDamage} is reached; {@code NONE} never breaks.</li>
 *   <li>{@code stops}: the bullet never passes this material.</li>
 * </ul>
 * Permission, gamerule and unbreakable-block policy is enforced by the caller; as a safety net,
 * any state with hardness &lt; 0 always resolves to {@link #UNBREAKABLE}.
 *
 * <p>Modifier values: the JSON {@code surfaces} object holds hand-authored approximations. On every
 * data reload the user's own export at {@link SurfacePropertiesImporter#path()} (if present) overrides
 * them. For a surface {@code S} each modifier is taken from the first source that has it:
 * the imported {@code S}, then its imported {@code base} chain, then the authored {@code S}, then the
 * imported {@code default} chain, then the authored {@code default}. Break mode, break damage and
 * {@code stops} always come from the JSON block entry, never from the import.
 *
 * <p>JSON shape:
 * <pre>{@code
 * {
 *   "surfaces": {
 *     "default": {"distance_modifier": 0.5, "damage_modifier": 0.5},
 *     "glass":   {"distance_modifier": 0.95, "damage_modifier": 0.9}
 *   },
 *   "blocks": [
 *     {"surface": "glass", "blocks": ["minecraft:glass_pane", "#c:glass_panes"], "break": "shatter"},
 *     {"surface": "Wood", "blocks": ["#minecraft:planks"], "break": "cumulative", "break_damage": 60}
 *   ],
 *   "fallback": [
 *     {"surface": "foliage", "max_hardness": 0.3},
 *     {"surface": "blockbullets", "stops": true}
 *   ]
 * }
 * }</pre>
 * {@code surfaces.default} is required. Block entries match in file order (first match wins);
 * unmatched states use the first fallback tier whose {@code max_hardness} (absent = unbounded) is
 * &gt;= the state's hardness. A referenced surface must exist in the authored table or the import.
 */
public final class MaterialTable {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation FILE = CsmcMod.id("ballistics/surfaces.json");
    public static final ResourceLocation LISTENER_ID = CsmcMod.id("surfaces");

    private static final String DEFAULT_SURFACE = "default";

    public enum BreakMode {
        NONE,
        SHATTER,
        CUMULATIVE;

        static BreakMode parse(String raw) {
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new JsonParseException("break must be one of none, shatter, cumulative; got '" + raw + "'");
            }
        }
    }

    public record Material(
            String name,
            double distanceModifier,
            double damageModifier,
            BreakMode breakMode,
            double breakDamage,
            boolean stops) {
        public Material {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(breakMode, "breakMode");
            requireUnit("distance_modifier", distanceModifier);
            requireUnit("damage_modifier", damageModifier);
            if (breakMode == BreakMode.CUMULATIVE && !(breakDamage > 0.0 && Double.isFinite(breakDamage))) {
                throw new IllegalArgumentException("break_damage must be a positive finite number for cumulative break, got " + breakDamage);
            }
        }

        private static void requireUnit(String key, double value) {
            if (!(value >= 0.0 && value <= 1.0)) {
                throw new IllegalArgumentException(key + " must be in [0, 1], got " + value);
            }
        }
    }

    /** Returned for hardness &lt; 0 (bedrock, barriers, ...): never passable, never breakable. */
    public static final Material UNBREAKABLE = new Material("unbreakable", 0.0, 0.0, BreakMode.NONE, 0.0, true);

    /** Used when no fallback tier matches (or no table is loaded). */
    private static final Material NO_TIER = new Material("blockbullets", 0.0, 0.0, BreakMode.NONE, 0.0, true);

    private record Entry(Material material, Set<Block> blocks, List<TagKey<Block>> tags) {
        boolean matches(BlockState state) {
            if (blocks.contains(state.getBlock())) {
                return true;
            }
            for (TagKey<Block> tag : tags) {
                if (state.is(tag)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Tier(Material material, double maxHardness) {}

    private record Snapshot(List<Entry> entries, List<Tier> fallback, Map<BlockState, Material> cache) {
        Material resolve(BlockState state) {
            float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (hardness < 0.0F) {
                return UNBREAKABLE;
            }
            for (Entry entry : entries) {
                if (entry.matches(state)) {
                    return entry.material();
                }
            }
            for (Tier tier : fallback) {
                if (hardness <= tier.maxHardness()) {
                    return tier.material();
                }
            }
            return NO_TIER;
        }

        Snapshot withClearedCache() {
            return new Snapshot(entries, fallback, new ConcurrentHashMap<>());
        }
    }

    /** Authored modifiers for one surface. */
    private record Authored(double distanceModifier, double damageModifier) {}

    /** Resolves surface names to modifiers from the authored table plus the user's import. */
    private record Surfaces(Map<String, Authored> authored, SurfacePropertiesImporter.Overrides imported) {
        boolean contains(String name) {
            return authored.containsKey(SurfacePropertiesImporter.key(name)) || imported.contains(name);
        }

        double distanceModifier(String name) {
            return pick(name, imported.distanceModifier(name), imported.distanceModifier(DEFAULT_SURFACE),
                    Authored::distanceModifier);
        }

        double damageModifier(String name) {
            return pick(name, imported.damageModifier(name), imported.damageModifier(DEFAULT_SURFACE),
                    Authored::damageModifier);
        }

        private double pick(String name, OptionalDouble importedOwn, OptionalDouble importedDefault,
                ToDoubleFunction<Authored> field) {
            if (importedOwn.isPresent()) {
                return importedOwn.getAsDouble();
            }
            Authored own = authored.get(SurfacePropertiesImporter.key(name));
            if (own != null) {
                return field.applyAsDouble(own);
            }
            if (importedDefault.isPresent()) {
                return importedDefault.getAsDouble();
            }
            return field.applyAsDouble(authored.get(DEFAULT_SURFACE));
        }
    }

    private static volatile Snapshot current = new Snapshot(List.of(), List.of(), new ConcurrentHashMap<>());
    private static boolean registered;

    private MaterialTable() {}

    /** Material for a block state; never null. Thread-safe; results are cached per state until reload. */
    public static Material lookup(BlockState state) {
        Snapshot snapshot = current;
        return snapshot.cache().computeIfAbsent(state, snapshot::resolve);
    }

    /** Registers the SERVER_DATA reload listener and tag-reload cache invalidation. Idempotent. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return LISTENER_ID;
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                MaterialTable.reload(manager);
            }
        });
        // Tags are bound after data reload listeners run; drop cached lookups so tag matches are fresh.
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (!client) {
                current = current.withClearedCache();
            }
        });
    }

    static void reload(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isEmpty()) {
            LOGGER.warn("[csmc] {} not found; using hardness fallback only", FILE);
            current = new Snapshot(List.of(), List.of(), new ConcurrentHashMap<>());
            return;
        }
        SurfacePropertiesImporter.Overrides imported = importUserSurfaces();
        try (Reader reader = resource.get().openAsReader()) {
            Snapshot parsed = parse(JsonParser.parseReader(reader), imported);
            current = parsed;
            LOGGER.info("[csmc] Loaded {} surface block mappings and {} fallback tiers from {} ({} imported surfaces)",
                    parsed.entries().size(), parsed.fallback().size(), FILE, imported.surfaces().size());
        } catch (Exception e) {
            LOGGER.error("[csmc] Failed to load {}; keeping previous surface table: {}", FILE, e.getMessage(), e);
        }
    }

    /** Reads the user's exported surface properties; any failure falls back to the authored values. */
    private static SurfacePropertiesImporter.Overrides importUserSurfaces() {
        Path path = SurfacePropertiesImporter.path();
        try {
            SurfacePropertiesImporter.Overrides imported = SurfacePropertiesImporter.load(path);
            if (imported.surfaces().isEmpty()) {
                LOGGER.info("[csmc] No surface properties imported from {}; using authored surface values", path);
            }
            return imported;
        } catch (Exception e) {
            LOGGER.error("[csmc] Failed to import {}; using authored surface values: {}", path, e.getMessage());
            return SurfacePropertiesImporter.Overrides.NONE;
        }
    }

    private static Snapshot parse(JsonElement root, SurfacePropertiesImporter.Overrides imported) {
        if (root == null || !root.isJsonObject()) {
            throw new JsonParseException("surfaces file must be a JSON object");
        }
        JsonObject json = root.getAsJsonObject();
        Surfaces surfaces = new Surfaces(parseAuthored(json), imported);
        List<Entry> entries = new ArrayList<>();
        for (JsonObject obj : objects(json, "blocks")) {
            String label = WeaponData.optString(obj, "surface", "blocks#" + entries.size());
            try {
                entries.add(parseEntry(obj, surfaces));
            } catch (RuntimeException e) {
                LOGGER.error("[csmc] Skipping surface mapping '{}': {}", label, e.getMessage());
            }
        }
        List<Tier> fallback = new ArrayList<>();
        for (JsonObject obj : objects(json, "fallback")) {
            String label = WeaponData.optString(obj, "surface", "fallback#" + fallback.size());
            try {
                fallback.add(new Tier(parseMaterial(obj, surfaces),
                        WeaponData.optDouble(obj, "max_hardness", Double.POSITIVE_INFINITY)));
            } catch (RuntimeException e) {
                LOGGER.error("[csmc] Skipping surface fallback tier '{}': {}", label, e.getMessage());
            }
        }
        return new Snapshot(List.copyOf(entries), List.copyOf(fallback), new ConcurrentHashMap<>());
    }

    private static Map<String, Authored> parseAuthored(JsonObject json) {
        JsonElement element = json.get("surfaces");
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("surfaces must be an object of surface name -> modifiers");
        }
        Map<String, Authored> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonObject()) {
                throw new JsonParseException("surfaces." + e.getKey() + " must be an object");
            }
            JsonObject obj = e.getValue().getAsJsonObject();
            double distance = WeaponData.reqDouble(obj, "distance_modifier");
            double damage = WeaponData.reqDouble(obj, "damage_modifier");
            if (!(distance >= 0.0 && distance <= 1.0) || !(damage >= 0.0 && damage <= 1.0)) {
                throw new JsonParseException("surfaces." + e.getKey() + " modifiers must be in [0, 1]");
            }
            if (out.put(SurfacePropertiesImporter.key(e.getKey()), new Authored(distance, damage)) != null) {
                throw new JsonParseException("surfaces." + e.getKey() + " is defined twice (names are case-insensitive)");
            }
        }
        if (!out.containsKey(DEFAULT_SURFACE)) {
            throw new JsonParseException("surfaces.default is required");
        }
        return Map.copyOf(out);
    }

    private static List<JsonObject> objects(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException(key + " must be an array of objects");
        }
        List<JsonObject> out = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                throw new JsonParseException(key + "[" + i + "] must be an object");
            }
            out.add(array.get(i).getAsJsonObject());
        }
        return out;
    }

    private static Material parseMaterial(JsonObject obj, Surfaces surfaces) {
        String surface = WeaponData.optString(obj, "surface", null);
        if (surface == null || surface.isBlank()) {
            throw new JsonParseException("missing required field 'surface'");
        }
        if (!surfaces.contains(surface)) {
            throw new JsonParseException("unknown surface '" + surface + "' (not authored in surfaces and not imported)");
        }
        BreakMode mode = BreakMode.parse(WeaponData.optString(obj, "break", "none"));
        try {
            return new Material(
                    surface,
                    surfaces.distanceModifier(surface),
                    surfaces.damageModifier(surface),
                    mode,
                    WeaponData.optDouble(obj, "break_damage", 0.0),
                    WeaponData.optBoolean(obj, "stops", false));
        } catch (IllegalArgumentException e) {
            throw new JsonParseException("surface '" + surface + "': " + e.getMessage(), e);
        }
    }

    private static Entry parseEntry(JsonObject obj, Surfaces surfaces) {
        Material material = parseMaterial(obj, surfaces);
        JsonElement blocksElement = obj.get("blocks");
        if (blocksElement == null || !blocksElement.isJsonArray() || blocksElement.getAsJsonArray().isEmpty()) {
            throw new JsonParseException("blocks must be a non-empty array of block ids or #tags");
        }
        Set<Block> blocks = new HashSet<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (JsonElement token : blocksElement.getAsJsonArray()) {
            if (!(token instanceof JsonPrimitive p) || !p.isString()) {
                throw new JsonParseException("blocks entries must be strings, got " + token);
            }
            String raw = p.getAsString();
            boolean isTag = raw.startsWith("#");
            ResourceLocation rl = ResourceLocation.tryParse(isTag ? raw.substring(1) : raw);
            if (rl == null) {
                throw new JsonParseException("'" + raw + "' is not a valid block id or #tag");
            }
            if (isTag) {
                tags.add(TagKey.create(Registries.BLOCK, rl));
            } else if (BuiltInRegistries.BLOCK.containsKey(rl)) {
                blocks.add(BuiltInRegistries.BLOCK.get(rl));
            } else {
                // Optional-mod blocks: tolerate and keep loading.
                LOGGER.debug("[csmc] Surface '{}': unknown block '{}' ignored", material.name(), rl);
            }
        }
        return new Entry(material, Set.copyOf(blocks), List.copyOf(tags));
    }
}
