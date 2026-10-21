package dev.csmc.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads bullet-penetration modifiers from the user's own exported {@code surfaceproperties_game.txt}
 * ({@link CachePaths#root()}{@code /data/surfaceproperties_game.txt}). Nothing from that file is
 * shipped with the mod; when it is absent the hand-authored values in {@code surfaces.json} apply.
 *
 * <p>Accepts both text layouts Valve has used:
 * <ul>
 *   <li>KeyValues 1: {@code "name" { "base" "parent" "bulletPenetrationDistanceModifier" "0.5" }},
 *       optionally nested under a root key; {@code //} comments, {@code #include}/{@code #base}
 *       lines and {@code [$PLATFORM]} conditionals are ignored.</li>
 *   <li>KV3 text: {@code SurfacePropertiesList = [ { surfacePropertyName = "name" ... } ]}.</li>
 * </ul>
 * Surface names and keys are case-insensitive. Repeated definitions of a name merge field by field,
 * later values winning. Parent links come from {@code base}, {@code baseSurfaceProperty} or
 * {@code baseSurfacePropertyName}.
 */
public final class SurfacePropertiesImporter {
    public static final String RELATIVE_PATH = "data/surfaceproperties_game.txt";

    private static final String NAME_KEY = "surfacepropertyname";
    private static final String DISTANCE_KEY = "bulletpenetrationdistancemodifier";
    private static final String DAMAGE_KEY = "bulletpenetrationdamagemodifier";
    private static final Set<String> BASE_KEYS = Set.of("base", "basesurfaceproperty", "basesurfacepropertyname");
    /** Keys that mark a keyed KV1 block as a surface definition. */
    private static final Set<String> SURFACE_MARKERS =
            Set.of(DISTANCE_KEY, DAMAGE_KEY, "gamematerial", "base", "basesurfaceproperty", "basesurfacepropertyname");

    /** One surface as written in the file; null fields are absent (inherited at resolve time). */
    public record Surface(String name, String base, Double distanceModifier, Double damageModifier) {
        Surface mergedWith(Surface later) {
            return new Surface(
                    later.name,
                    later.base != null ? later.base : base,
                    later.distanceModifier != null ? later.distanceModifier : distanceModifier,
                    later.damageModifier != null ? later.damageModifier : damageModifier);
        }
    }

    /** Imported surfaces keyed by lower-case name, with base-chain resolution. */
    public record Overrides(Map<String, Surface> surfaces) {
        public static final Overrides NONE = new Overrides(Map.of());

        public Overrides {
            surfaces = Map.copyOf(surfaces);
        }

        public boolean contains(String name) {
            return surfaces.containsKey(key(name));
        }

        /** Distance modifier from the surface itself or its explicit {@code base} chain. */
        public OptionalDouble distanceModifier(String name) {
            return resolve(name, Surface::distanceModifier);
        }

        /** Damage modifier from the surface itself or its explicit {@code base} chain. */
        public OptionalDouble damageModifier(String name) {
            return resolve(name, Surface::damageModifier);
        }

        private OptionalDouble resolve(String name, Function<Surface, Double> field) {
            Set<String> seen = new HashSet<>();
            String current = key(name);
            while (current != null && seen.add(current)) {
                Surface surface = surfaces.get(current);
                if (surface == null) {
                    break;
                }
                Double value = field.apply(surface);
                if (value != null) {
                    return OptionalDouble.of(value);
                }
                current = surface.base() == null ? null : key(surface.base());
            }
            return OptionalDouble.empty();
        }
    }

    private SurfacePropertiesImporter() {}

    /** Location of the user's export; may not exist. */
    public static Path path() {
        return CachePaths.root().resolve(RELATIVE_PATH);
    }

    /** Reads {@code file}; returns {@link Overrides#NONE} if it does not exist. */
    public static Overrides load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Overrides.NONE;
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses KV1 or KV3 text.
     *
     * @throws IllegalArgumentException on malformed structure or non-numeric modifier values
     */
    public static Overrides parse(String text) {
        Object root = new Parser(new Lexer(text).tokens()).document();
        Map<String, Surface> out = new LinkedHashMap<>();
        collect(root, null, out);
        return new Overrides(out);
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ---- extraction -------------------------------------------------------------------------

    private static void collect(Object node, String keyName, Map<String, Surface> out) {
        if (node instanceof Obj obj) {
            String explicitName = obj.scalar(NAME_KEY);
            if (explicitName != null || (keyName != null && obj.hasAny(SURFACE_MARKERS))) {
                add(toSurface(explicitName != null ? explicitName : keyName, obj), out);
                return;
            }
            for (Map.Entry<String, Object> child : obj.entries()) {
                collect(child.getValue(), child.getKey(), out);
            }
        } else if (node instanceof List<?> list) {
            for (Object element : list) {
                collect(element, null, out);
            }
        }
    }

    private static Surface toSurface(String name, Obj obj) {
        String base = null;
        for (String baseKey : BASE_KEYS) {
            String value = obj.scalar(baseKey);
            if (value != null && !value.isBlank()) {
                base = value;
            }
        }
        return new Surface(name, base, number(name, obj, DISTANCE_KEY), number(name, obj, DAMAGE_KEY));
    }

    private static Double number(String surface, Obj obj, String key) {
        String raw = obj.scalar(key);
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("surface '" + surface + "': " + key
                    + " must be a finite number in [0, 1]: '" + raw + "'");
        }
    }

    private static void add(Surface surface, Map<String, Surface> out) {
        out.merge(key(surface.name()), surface, Surface::mergedWith);
    }

    // ---- generic KV1/KV3 text tree ----------------------------------------------------------

    /** Ordered multimap object; keys stored lower-case, last scalar wins on lookup. */
    private record Obj(List<Map.Entry<String, Object>> entries) {
        String scalar(String lowerKey) {
            String found = null;
            for (Map.Entry<String, Object> e : entries) {
                if (e.getKey().equals(lowerKey) && e.getValue() instanceof String s) {
                    found = s;
                }
            }
            return found;
        }

        boolean hasAny(Set<String> lowerKeys) {
            for (Map.Entry<String, Object> e : entries) {
                if (lowerKeys.contains(e.getKey())) {
                    return true;
                }
            }
            return false;
        }
    }

    private enum Kind { STRING, OPEN_BRACE, CLOSE_BRACE, OPEN_BRACKET, CLOSE_BRACKET }

    private record Token(Kind kind, String text, int line) {}

    private static final class Lexer {
        private final String s;
        private int i;
        private int line = 1;

        Lexer(String s) {
            this.s = s;
        }

        List<Token> tokens() {
            List<Token> out = new ArrayList<>();
            while (true) {
                skipTrivia();
                if (i >= s.length()) {
                    return out;
                }
                char c = s.charAt(i);
                switch (c) {
                    case '{' -> { out.add(new Token(Kind.OPEN_BRACE, "{", line)); i++; }
                    case '}' -> { out.add(new Token(Kind.CLOSE_BRACE, "}", line)); i++; }
                    case ']' -> { out.add(new Token(Kind.CLOSE_BRACKET, "]", line)); i++; }
                    case '[' -> {
                        if (s.startsWith("[$", i) || s.startsWith("[!$", i)) {
                            skipPast(']'); // KV1 platform conditional
                        } else {
                            out.add(new Token(Kind.OPEN_BRACKET, "[", line));
                            i++;
                        }
                    }
                    case '=', ',' -> i++;
                    case '"' -> out.add(new Token(Kind.STRING, quoted(), line));
                    default -> out.add(new Token(Kind.STRING, bare(), line));
                }
            }
        }

        private void skipTrivia() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\n') {
                    line++;
                    i++;
                } else if (Character.isWhitespace(c) || c == '\uFEFF') {
                    i++;
                } else if (s.startsWith("//", i) || (c == '#' && atLineStart())) {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                } else if (s.startsWith("/*", i)) {
                    skipBlock("*/");
                } else if (s.startsWith("<!--", i)) {
                    skipBlock("-->");
                } else {
                    return;
                }
            }
        }

        private boolean atLineStart() {
            for (int j = i - 1; j >= 0; j--) {
                char c = s.charAt(j);
                if (c == '\n') {
                    return true;
                }
                if (!Character.isWhitespace(c)) {
                    return false;
                }
            }
            return true;
        }

        private void skipBlock(String end) {
            int close = s.indexOf(end, i);
            int stop = close < 0 ? s.length() : close + end.length();
            countLines(i, stop);
            i = stop;
        }

        private void skipPast(char end) {
            int close = s.indexOf(end, i);
            i = close < 0 ? s.length() : close + 1;
        }

        private String quoted() {
            int startLine = line;
            if (s.startsWith("\"\"\"", i)) {
                int close = s.indexOf("\"\"\"", i + 3);
                if (close < 0) {
                    throw new IllegalArgumentException("unterminated multi-line string at line " + startLine);
                }
                String value = s.substring(i + 3, close);
                countLines(i, close);
                i = close + 3;
                return value;
            }
            StringBuilder b = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    b.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> n;
                    });
                    continue;
                }
                if (c == '\n') {
                    line++;
                }
                b.append(c);
            }
            throw new IllegalArgumentException("unterminated string at line " + startLine);
        }

        private String bare() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) || "{}[]=,\"".indexOf(c) >= 0) {
                    break;
                }
                i++;
            }
            String word = s.substring(start, i);
            // KV3 typed strings such as resource_name:"path" form one value.
            if (word.endsWith(":") && i < s.length() && s.charAt(i) == '"') {
                return quoted();
            }
            return word;
        }

        private void countLines(int from, int to) {
            for (int j = from; j < to; j++) {
                if (s.charAt(j) == '\n') {
                    line++;
                }
            }
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int p;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Object document() {
            if (!tokens.isEmpty() && tokens.get(0).kind() == Kind.OPEN_BRACE) {
                p++;
                Obj root = objectBody(Kind.CLOSE_BRACE);
                if (p < tokens.size()) {
                    throw error(tokens.get(p), "unexpected content after root object");
                }
                return root;
            }
            return objectBody(null);
        }

        /** Reads key/value pairs until {@code end} (consumed) or end of input when {@code end} is null. */
        private Obj objectBody(Kind end) {
            List<Map.Entry<String, Object>> entries = new ArrayList<>();
            while (true) {
                if (p >= tokens.size()) {
                    if (end != null) {
                        throw new IllegalArgumentException("unexpected end of input; missing '}'");
                    }
                    return new Obj(entries);
                }
                Token t = tokens.get(p++);
                if (end != null && t.kind() == end) {
                    return new Obj(entries);
                }
                if (t.kind() != Kind.STRING) {
                    throw error(t, "expected key, got '" + t.text() + "'");
                }
                entries.add(Map.entry(key(t.text()), value()));
            }
        }

        private Object value() {
            if (p >= tokens.size()) {
                throw new IllegalArgumentException("unexpected end of input; missing value");
            }
            Token t = tokens.get(p++);
            return switch (t.kind()) {
                case STRING -> t.text();
                case OPEN_BRACE -> objectBody(Kind.CLOSE_BRACE);
                case OPEN_BRACKET -> array();
                default -> throw error(t, "expected value, got '" + t.text() + "'");
            };
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            while (true) {
                if (p >= tokens.size()) {
                    throw new IllegalArgumentException("unexpected end of input; missing ']'");
                }
                if (tokens.get(p).kind() == Kind.CLOSE_BRACKET) {
                    p++;
                    return out;
                }
                out.add(value());
            }
        }

        private static IllegalArgumentException error(Token t, String message) {
            return new IllegalArgumentException(message + " at line " + t.line());
        }
    }
}
