package dev.csmc.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.csmc.CsmcMod;
import dev.csmc.data.MeshSets;
import dev.csmc.grenade.GrenadeKind;
import dev.csmc.item.GunItem;
import dev.csmc.item.KnifeItem;
import dev.csmc.melee.ViewmodelAdjustment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-only, user-authored overrides in {@code config/csmc-gun-rendering.json}; no exported assets are copied
 * there. Top-level keys are weapon ids; the key {@code "*"} applies to every weapon. Per weapon:
 * {@code contexts} (display transforms), {@code animations} (action→clip), {@code mesh_sets} (preferred CS2 mesh
 * group order) and {@code texture_max_edge} (pixels).
 *
 * <p>Defaults assume the glTF convention the CS2 exporter produces: +Y up, barrel along +Z, metres. Hand
 * contexts draw the model at real size pivoted on its {@code ag1_hand_r} grip node; other contexts fit the model's
 * largest extent to one block. {@code scale} multiplies either normalisation.
 *
 * <p>Utility items (grenade kind ids such as {@code hegrenade}) have their own defaults: the CS2 world models are
 * metre-scale with the long axis along +Y and the same {@code ag1_hand_r} grip convention, so hands keep the weapon
 * rotations while GUI/ground/frames get upright, smaller fits. Knife world models have the blade along +Y; in hand
 * contexts the renderer first turns it onto +Z, so hand transforms and procedural swings treat the blade like a
 * barrel (first person larger and tipped toward the crosshair, third person tipped up like a sword). In GUI slots
 * knives show diagonally like vanilla swords. Gun-tuned {@code "*"} contexts apply to neither; configure them under
 * their own id.
 */
final class GunTransforms {
    static final int DEFAULT_TEXTURE_MAX_EDGE = 1024;
    private static final String ALL = "*";
    /** Scratch for {@link #firstPersonAdjustment}; render thread only. */
    private static final float[] ADJUST_CONFIGURED = new float[7];
    private static final float[] ADJUST_DEFAULT = new float[7];
    private static final Quaternionf ADJUST_A = new Quaternionf();
    private static final Quaternionf ADJUST_B = new Quaternionf();
    private static final Transform GUI = new Transform(0, 0, 0, 10, 90, 0, .95f);
    /** First person looks along -Z: turn the +Z barrel around. */
    private static final Transform FIRST = new Transform(0, 0, 0, 0, 180, 0, 1);
    /**
     * Third person (ItemInHandLayer): item +Y points forward of the body and -Z down the arm, so roll the gun
     * upright about its barrel, then pitch the barrel from +Z to +Y.
     */
    private static final Transform THIRD = new Transform(0, 0, 0, -90, 0, 180, 1);
    private static final Transform GROUND = new Transform(0, 0, 0, 0, 90, 0, .5f);
    private static final Transform OTHER = new Transform(0, 0, 0, 0, 90, 0, .8f);
    private static final Transform UTILITY_GUI = new Transform(0, 0, 0, 15, 45, -20, .9f);
    private static final Transform UTILITY_GROUND = new Transform(0, 0, 0, 0, 0, 0, .3f);
    private static final Transform UTILITY_OTHER = new Transform(0, 0, 0, 0, 45, 0, .6f);
    /** Blade from +Z to the screen diagonal with its flat facing the viewer (Euler of rollZ(45)·yaw(90)). */
    private static final Transform KNIFE_GUI = new Transform(0, 0, 0, -90, 45, 90, 1);
    private static final Transform KNIFE_GROUND = new Transform(0, 0, 0, 0, 90, 0, .4f);
    private static final Transform KNIFE_OTHER = new Transform(0, 0, 0, 0, 90, 0, .7f);
    /** {@link #FIRST} raised into view, blade tipped up 20° and 10° toward the crosshair, larger like a CS viewmodel. */
    private static final Transform KNIFE_FIRST = new Transform(-.1f, .15f, 0, -159.72f, -9.39f, -176.55f, 1.5f);
    /** {@link #THIRD} with the blade tipped up 30°, like a held sword. */
    private static final Transform KNIFE_THIRD = new Transform(0, 0, 0, -60, 0, 180, 1);

    /** Which default family an item uses. */
    enum Kind { GUN, KNIFE, UTILITY }

    static Kind kind(Item item) {
        if (item instanceof KnifeItem) return Kind.KNIFE;
        return item instanceof GunItem ? Kind.GUN : Kind.UTILITY;
    }

    private final Map<String, Settings> weapons = new HashMap<>();
    private Settings global = new Settings();

    void reload() {
        weapons.clear();
        global = new Settings();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("csmc-gun-rendering.json");
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (var entry : root.entrySet()) {
                Settings settings = parse(entry.getValue().getAsJsonObject(), kind(entry.getKey()));
                if (ALL.equals(entry.getKey())) global = settings;
                else weapons.put(entry.getKey(), settings);
            }
        } catch (IOException | RuntimeException e) {
            weapons.clear();
            global = new Settings();
            CsmcMod.LOGGER.warn("[csmc glTF] Cannot read {}; using default transforms", file, e);
        }
    }

    private static Settings parse(JsonObject weapon, Kind kind) {
        Settings settings = new Settings();
        if (weapon.has("contexts")) {
            for (var context : weapon.getAsJsonObject("contexts").entrySet()) {
                ItemDisplayContext mode = ItemDisplayContext.valueOf(context.getKey().toUpperCase(Locale.ROOT));
                JsonObject values = context.getValue().getAsJsonObject();
                Transform defaults = defaults(mode, kind);
                float[] t = vector(values, "translation", new float[]{defaults.x, defaults.y, defaults.z});
                float[] r = vector(values, "rotation", new float[]{defaults.rx, defaults.ry, defaults.rz});
                float scale = values.has("scale") ? values.get("scale").getAsFloat() : defaults.scale;
                if (!Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("scale must be positive");
                settings.contexts.put(mode, new Transform(t[0], t[1], t[2], r[0], r[1], r[2], scale));
            }
        }
        if (weapon.has("animations")) {
            weapon.getAsJsonObject("animations").entrySet().forEach(e -> settings.animations.put(e.getKey(), e.getValue().getAsString()));
        }
        if (weapon.has("mesh_sets")) {
            JsonElement sets = weapon.get("mesh_sets");
            List<String> order = new ArrayList<>();
            if (sets.isJsonArray()) sets.getAsJsonArray().forEach(e -> order.add(e.getAsString()));
            else order.add(sets.getAsString());
            settings.meshSets = List.copyOf(order);
        }
        if (weapon.has("texture_max_edge")) {
            int edge = weapon.get("texture_max_edge").getAsInt();
            if (edge < 1) throw new IllegalArgumentException("texture_max_edge must be positive");
            settings.textureMaxEdge = edge;
        }
        return settings;
    }

    Transform transform(String id, ItemDisplayContext context, Kind kind) {
        Settings settings = weapons.get(id);
        Transform configured = settings == null ? null : settings.contexts.get(context);
        if (configured == null && kind == Kind.GUN) configured = global.contexts.get(context);
        return configured != null ? configured : defaults(context, kind);
    }

    /**
     * A first-person override for a camera-space clip viewmodel, as an adjustment relative to the built-in default
     * for that context, applied about the hand ({@link ViewmodelAdjustment}). Same precedence as {@link #transform}:
     * per-weapon, then (guns only) the global {@code "*"} context, then identity. Allocation-free; render thread only.
     */
    Matrix4f firstPersonAdjustment(String id, boolean leftHand, Matrix4f out) {
        ItemDisplayContext context = leftHand ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        Settings settings = weapons.get(id);
        Transform configured = settings == null ? null : settings.contexts.get(context);
        Kind kind = kind(id);
        if (configured == null && kind == Kind.GUN) configured = global.contexts.get(context);
        if (configured == null) return out.identity();
        return ViewmodelAdjustment.adjustment(configured.values(ADJUST_CONFIGURED),
                defaults(context, kind).values(ADJUST_DEFAULT), leftHand, out, ADJUST_A, ADJUST_B);
    }

    /** User-configured clip for an action, or null to use the cache manifest. */
    String animationOverride(String id, String action) {
        Settings settings = weapons.get(id);
        String clip = settings == null ? null : settings.animations.get(action);
        return clip != null ? clip : global.animations.get(action);
    }

    List<String> meshSets(String id) {
        Settings settings = weapons.get(id);
        if (settings != null && settings.meshSets != null) return settings.meshSets;
        return global.meshSets != null ? global.meshSets : MeshSets.DEFAULT_PREFERENCE;
    }

    int textureMaxEdge(String id) {
        Settings settings = weapons.get(id);
        if (settings != null && settings.textureMaxEdge > 0) return settings.textureMaxEdge;
        return global.textureMaxEdge > 0 ? global.textureMaxEdge : DEFAULT_TEXTURE_MAX_EDGE;
    }

    /** Hand contexts use the real-size, grip-pivoted normalisation; the rest fit to one block. */
    static boolean inHand(ItemDisplayContext context) {
        return context.firstPerson() || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    private static float[] vector(JsonObject object, String key, float[] defaults) {
        if (!object.has(key)) return defaults;
        JsonArray array = object.getAsJsonArray(key);
        if (array.size() != 3) throw new IllegalArgumentException(key + " must have three numbers");
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            result[i] = array.get(i).getAsFloat();
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException(key + " must be finite");
        }
        return result;
    }

    private static Kind kind(String id) {
        if (GrenadeKind.byId(id) != null || "healthshot".equals(id) || "c4".equals(id) || "defuse_kit".equals(id)) {
            return Kind.UTILITY;
        }
        return CsmcMod.KNIVES.containsKey(id) ? Kind.KNIFE : Kind.GUN;
    }

    private static Transform defaults(ItemDisplayContext context, Kind kind) {
        switch (context) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> { return kind == Kind.KNIFE ? KNIFE_FIRST : FIRST; }
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> { return kind == Kind.KNIFE ? KNIFE_THIRD : THIRD; }
            default -> { }
        }
        return switch (kind) {
            case UTILITY -> context == ItemDisplayContext.GUI ? UTILITY_GUI
                    : context == ItemDisplayContext.GROUND ? UTILITY_GROUND : UTILITY_OTHER;
            case KNIFE -> context == ItemDisplayContext.GUI ? KNIFE_GUI
                    : context == ItemDisplayContext.GROUND ? KNIFE_GROUND : KNIFE_OTHER;
            case GUN -> context == ItemDisplayContext.GUI ? GUI
                    : context == ItemDisplayContext.GROUND ? GROUND : OTHER;
        };
    }

    private static final class Settings {
        final Map<ItemDisplayContext, Transform> contexts = new EnumMap<>(ItemDisplayContext.class);
        final Map<String, String> animations = new HashMap<>();
        List<String> meshSets;
        int textureMaxEdge;
    }

    record Transform(float x, float y, float z, float rx, float ry, float rz, float scale) {
        void apply(PoseStack poses, boolean leftHand, Quaternionf rotation) {
            float mirror = leftHand ? -1 : 1;
            poses.translate(x * mirror, y, z);
            poses.mulPose(rotation.rotationXYZ((float)Math.toRadians(rx), (float)Math.toRadians(ry * mirror), (float)Math.toRadians(rz * mirror)));
            poses.scale(scale, scale, scale);
        }

        /** Copies {@code {x, y, z, rx, ry, rz, scale}} into {@code out}. */
        float[] values(float[] out) {
            out[0] = x;
            out[1] = y;
            out[2] = z;
            out[3] = rx;
            out[4] = ry;
            out[5] = rz;
            out[6] = scale;
            return out;
        }
    }
}
