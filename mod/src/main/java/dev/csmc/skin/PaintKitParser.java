package dev.csmc.skin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** KV1 items_game reader. Duplicate object sections merge instead of discarding earlier paint kits. */
public final class PaintKitParser {
    private static final Pattern PAIR = Pattern.compile("\\[([\\w-]+)\\]weapon_(\\w+)");
    private PaintKitParser() { }

    public record PaintKit(int id, String name, int style, float wearMin, float wearMax,
                           String compositeMaterial, boolean legacyModel, Set<String> weapons) { }

    public static Map<Integer, PaintKit> parse(String text) {
        Map<String, Object> game = object(new Reader(text).parse(), "items_game");
        Map<String, Object> definitions = object(game, "paint_kits");
        Map<String, Object> defaults = object(definitions, "0");
        Map<String, Set<String>> pairs = new LinkedHashMap<>();
        associations(object(game, "client_loot_lists"), pairs);
        associations(object(game, "item_sets"), pairs);
        Map<Integer, PaintKit> result = new LinkedHashMap<>();
        for (var entry : definitions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>) || !entry.getKey().matches("[0-9]+")) continue;
            int id = Integer.parseInt(entry.getKey());
            if (id == 0 || id >= 10000) continue;
            Map<String, Object> kit = object(definitions, entry.getKey());
            String name = string(kit, "name", "");
            float min = Float.parseFloat(string(kit, "wear_remap_min", string(defaults, "wear_remap_min", "0")));
            float max = Float.parseFloat(string(kit, "wear_remap_max", string(defaults, "wear_remap_max", "1")));
            if (!Float.isFinite(min) || !Float.isFinite(max) || min < 0 || max > 1 || min > max) {
                throw new IllegalArgumentException("Invalid paint kit wear range " + id);
            }
            result.put(id, new PaintKit(id, name, Integer.parseInt(string(kit, "style", "0")), min, max,
                    string(kit, "composite_material_path", "weapons/paints/legacy/" + name + ".vcompmat"),
                    "1".equals(string(kit, "use_legacy_model", "0")), Set.copyOf(pairs.getOrDefault(name, Set.of()))));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void associations(Map<String, Object> object, Map<String, Set<String>> pairs) {
        for (var entry : object.entrySet()) {
            var match = PAIR.matcher(entry.getKey());
            if (match.matches()) pairs.computeIfAbsent(match.group(1), key -> new LinkedHashSet<>()).add(match.group(2));
            if (entry.getValue() instanceof Map<?, ?>) associations(object(object, entry.getKey()), pairs);
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        return map.get(key) instanceof Map<?, ?> child ? (Map<String, Object>) child : Map.of();
    }
    private static String string(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String value ? value : fallback;
    }

    private static final class Reader {
        final String text;
        int pos;
        Reader(String text) { this.text = text; }
        Map<String, Object> parse() { return readObject(false); }
        Map<String, Object> readObject(boolean expectsClose) {
            Map<String, Object> result = new LinkedHashMap<>();
            while (true) {
                skip();
                if (pos == text.length()) {
                    if (expectsClose) throw new IllegalArgumentException("Unclosed KV1 object");
                    return result;
                }
                if (text.charAt(pos) == '}') {
                    if (!expectsClose) throw new IllegalArgumentException("Unexpected KV1 closer");
                    pos++;
                    return result;
                }
                String key = token();
                skip();
                Object value;
                if (pos < text.length() && text.charAt(pos) == '{') { pos++; value = readObject(true); }
                else value = token();
                if (result.get(key) instanceof Map<?, ?> && value instanceof Map<?, ?>) {
                    Map<String, Object> merged = new LinkedHashMap<>(object(result, key));
                    @SuppressWarnings("unchecked") Map<String, Object> next = (Map<String, Object>) value;
                    merge(merged, next);
                    result.put(key, merged);
                } else result.put(key, value);
            }
        }
        void merge(Map<String, Object> target, Map<String, Object> next) {
            for (var entry : next.entrySet()) {
                if (target.get(entry.getKey()) instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                    Map<String, Object> child = new LinkedHashMap<>(object(target, entry.getKey()));
                    merge(child, object(next, entry.getKey()));
                    target.put(entry.getKey(), child);
                } else target.put(entry.getKey(), entry.getValue());
            }
        }
        String token() {
            skip();
            if (pos == text.length() || text.charAt(pos) == '{' || text.charAt(pos) == '}') {
                throw new IllegalArgumentException("Expected KV1 token at " + pos);
            }
            StringBuilder out = new StringBuilder();
            if (text.charAt(pos) == '"') {
                pos++;
                while (pos < text.length()) {
                    char c = text.charAt(pos++);
                    if (c == '"') return out.toString();
                    if (c == '\\' && pos < text.length() && (text.charAt(pos) == '"' || text.charAt(pos) == '\\')) c = text.charAt(pos++);
                    out.append(c);
                }
                throw new IllegalArgumentException("Unclosed KV1 string");
            }
            int start = pos;
            while (pos < text.length() && !Character.isWhitespace(text.charAt(pos)) && "{}".indexOf(text.charAt(pos)) < 0) pos++;
            return text.substring(start, pos);
        }
        void skip() {
            while (pos < text.length()) {
                if (Character.isWhitespace(text.charAt(pos)) || text.charAt(pos) == '\ufeff') { pos++; continue; }
                if (text.startsWith("//", pos)) {
                    int end = text.indexOf('\n', pos + 2);
                    pos = end < 0 ? text.length() : end + 1;
                } else if (text.startsWith("/*", pos)) {
                    int end = text.indexOf("*/", pos + 2);
                    if (end < 0) throw new IllegalArgumentException("Unclosed KV1 comment");
                    pos = end + 2;
                } else break;
            }
        }
    }
}
