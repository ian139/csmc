package dev.csmc.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Authored per-weapon balance overrides from {@code data/<namespace>/weapon_balance/<path>.json}, applied as the
 * LAST layer, after placeholders, datapack weapon definitions and the local CS2 export
 * ({@link Cs2WeaponImporter}). Exported vdata therefore never overrides authored balance.
 *
 * <p>A file targets the weapon with the same id ({@code data/csmc/weapon_balance/ssg08.json} → {@code csmc:ssg08})
 * and may only contain the members in {@link #NUMBERS} plus the objects {@code inaccuracy} ({@link #INACCURACY}),
 * {@code recoil} ({@link #RECOIL}) and {@code scoped} ({@link #SCOPED}); every value must be a JSON number.
 * Keys starting with {@code _} are comments. Files are parsed as strict JSON holding exactly one value: broken
 * syntax or trailing content after the object rejects the file. A rejected file (syntax, any other member, a
 * non-numeric value, or no matching weapon) is logged and ignored as a whole; an override whose result fails
 * {@link WeaponData} validation is likewise ignored and the weapon keeps its pre-override definition.
 */
public final class WeaponBalance {
    public static final String DIRECTORY = "weapon_balance";

    /** Numeric top-level members, named as in the weapon JSON contract. */
    public static final Set<String> NUMBERS = Set.of(
            "damage", "headshot_multiplier", "armor_ratio", "armor_bonus", "range", "range_modifier",
            "penetration_power", "penetration_count", "penetration_distance", "cycle_time_s", "spread_base",
            "magazine_size", "reload_time_s", "max_speed");
    public static final Set<String> INACCURACY = Set.of("stand", "crouch", "move", "jump", "land", "ladder", "fire");
    public static final Set<String> RECOIL = Set.of("angle", "angle_variance", "magnitude", "magnitude_variance");
    public static final Set<String> SCOPED = Set.of("spread_base", "max_speed", "cycle_time_s", "inaccuracy");
    private static final Set<String> INTEGERS = Set.of("penetration_count", "magazine_size");
    private static final Gson GSON = new Gson();

    private WeaponBalance() {}

    /**
     * Validates override files given as raw text. Valid ones are returned keyed by weapon id with comment keys
     * removed; each rejected file adds one {@code "<id>: <reason>"} message to {@code errors}.
     */
    public static Map<ResourceLocation, JsonObject> parse(Map<ResourceLocation, String> files, List<String> errors) {
        Map<ResourceLocation, JsonObject> valid = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, String> file : files.entrySet()) {
            List<String> problems = new ArrayList<>();
            JsonElement element = parseStrict(file.getValue(), problems);
            JsonObject override = null;
            if (element != null) {
                if (element.isJsonObject()) {
                    override = validate(element.getAsJsonObject(), "", problems);
                } else {
                    problems.add("must be a JSON object");
                }
            }
            if (problems.isEmpty() && override.size() == 0) {
                problems.add("contains no balance fields");
            }
            if (problems.isEmpty()) {
                valid.put(file.getKey(), override);
            } else {
                errors.add(file.getKey() + ": " + String.join("; ", problems));
            }
        }
        return valid;
    }

    /**
     * One strict JSON value followed by end of document, or null with a problem recorded. Gson 2.10.1 has no
     * {@code Strictness.STRICT}; its non-lenient mode still accepts invalid string escapes (such as {@code \'})
     * and raw control characters in strings, so string literals are checked against RFC 8259 first.
     */
    static JsonElement parseStrict(String text, List<String> problems) {
        String stringProblem = invalidString(text);
        if (stringProblem != null) {
            problems.add("malformed JSON: " + stringProblem);
            return null;
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement element = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                problems.add("malformed JSON: trailing content after the object");
                return null;
            }
            return element;
        } catch (IOException | RuntimeException e) {
            problems.add("malformed JSON: " + e.getMessage());
            return null;
        }
    }

    /** First RFC 8259 string-literal violation (bad escape or unescaped control character), or null. */
    static String invalidString(String text) {
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inString) {
                inString = c == '"';
                continue;
            }
            if (c == '"') {
                inString = false;
            } else if (c < 0x20) {
                return "unescaped control character U+" + String.format("%04X", (int) c) + " in a string at offset " + i;
            } else if (c == '\\') {
                char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                if ("\"\\/bfnrt".indexOf(next) >= 0 && next != 0) {
                    i++;
                } else if (next == 'u' && i + 5 < text.length()
                        && text.substring(i + 2, i + 6).chars().allMatch(h -> Character.digit(h, 16) >= 0)) {
                    i += 5;
                } else {
                    return "invalid escape in a string at offset " + i;
                }
            }
        }
        return null;
    }

    /** Overlays a validated override onto a complete weapon definition (nested objects merge member-wise). */
    public static JsonObject apply(JsonObject definition, JsonObject override) {
        return Cs2WeaponImporter.overlay(definition, override);
    }

    private static JsonObject validate(JsonObject json, String path, List<String> problems) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")) {
                continue;
            }
            String name = path + key;
            JsonElement value = entry.getValue();
            Set<String> nested = switch (path) {
                case "" -> key.equals("inaccuracy") ? INACCURACY : key.equals("recoil") ? RECOIL
                        : key.equals("scoped") ? SCOPED : null;
                case "scoped." -> key.equals("inaccuracy") ? INACCURACY : null;
                default -> null;
            };
            Set<String> allowed = switch (path) {
                case "" -> NUMBERS;
                case "inaccuracy.", "scoped.inaccuracy." -> INACCURACY;
                case "recoil." -> RECOIL;
                case "scoped." -> SCOPED;
                default -> Set.of();
            };
            if (nested != null) {
                if (!value.isJsonObject()) {
                    problems.add(name + " must be an object");
                } else {
                    result.add(key, validate(value.getAsJsonObject(), name + ".", problems));
                }
            } else if (!allowed.contains(key)) {
                problems.add("field '" + name + "' is not a balance field");
            } else if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()
                    || !Double.isFinite(primitive.getAsDouble())) {
                problems.add(name + " must be a finite number");
            } else if (INTEGERS.contains(key) && primitive.getAsDouble() != Math.rint(primitive.getAsDouble())) {
                problems.add(name + " must be an integer");
            } else {
                result.add(key, value.deepCopy());
            }
        }
        return result;
    }
}
