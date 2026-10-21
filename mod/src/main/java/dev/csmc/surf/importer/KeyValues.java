package dev.csmc.surf.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bounded parser for the Valve KeyValues text syntax used by VMF files: {@code name { "key" "value" child { ... } }}.
 * Tokens are quoted strings (no escape sequences, as Hammer writes them), bare words, braces and {@code //} line
 * comments. Every limit in {@link ImportLimits} that applies to text is enforced while scanning, so a hostile or
 * corrupt file fails fast with a line number instead of exhausting memory or the stack.
 */
public final class KeyValues {
    private final String text;
    private final ImportLimits limits;
    private int pos;
    private int line = 1;
    private int nodes;

    private KeyValues(String text, ImportLimits limits) {
        this.text = text;
        this.limits = limits;
    }

    /** One {@code name { ... }} block: ordered key/value pairs and ordered child blocks. Names keep their case. */
    public record Node(String name, List<Pair> pairs, List<Node> children) {
        public Node {
            pairs = List.copyOf(pairs);
            children = List.copyOf(children);
        }

        /** First value for {@code key} (case-insensitive, as Source's KeyValues lookups are), or null. */
        public String get(String key) {
            for (Pair pair : pairs) if (pair.key().equalsIgnoreCase(key)) return pair.value();
            return null;
        }

        /** Child blocks named {@code name} (case-insensitive), in file order. */
        public List<Node> children(String name) {
            List<Node> out = new ArrayList<>();
            for (Node child : children) if (child.name().equalsIgnoreCase(name)) out.add(child);
            return out;
        }

        public Node child(String name) {
            for (Node child : children) if (child.name().equalsIgnoreCase(name)) return child;
            return null;
        }

        public String lowerName() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    public record Pair(String key, String value) {}

    /** Parses a whole file into a synthetic root node whose children are the top-level blocks. */
    public static Node parse(String text, ImportLimits limits) {
        if (text.length() > limits.maxBytes()) {
            throw new SurfImportException("file is larger than " + limits.maxBytes() + " bytes");
        }
        KeyValues parser = new KeyValues(text, limits);
        return parser.block("", 0, true);
    }

    private Node block(String name, int depth, boolean root) {
        if (depth > limits.maxDepth()) throw error("blocks nested deeper than " + limits.maxDepth());
        if (++nodes > limits.maxNodes()) throw error("more than " + limits.maxNodes() + " blocks");
        List<Pair> pairs = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        while (true) {
            Token token = next();
            if (token == null) {
                if (root) return new Node(name, pairs, children);
                throw error("unexpected end of file inside block '" + name + "'");
            }
            if (token.kind == Kind.CLOSE) {
                if (root) throw error("unmatched '}'");
                return new Node(name, pairs, children);
            }
            if (token.kind == Kind.OPEN) throw error("'{' without a block name");
            Token after = next();
            if (after == null) throw error("unexpected end of file after '" + token.text + "'");
            if (after.kind == Kind.OPEN) {
                children.add(block(token.text, depth + 1, false));
            } else if (after.kind == Kind.STRING) {
                if (pairs.size() >= limits.maxPairsPerBlock()) {
                    throw error("block '" + name + "' has more than " + limits.maxPairsPerBlock() + " keys");
                }
                pairs.add(new Pair(token.text, after.text));
            } else {
                throw error("key '" + token.text + "' has no value");
            }
        }
    }

    private enum Kind { OPEN, CLOSE, STRING }

    private record Token(Kind kind, String text) {}

    private Token next() {
        skipSpaceAndComments();
        if (pos >= text.length()) return null;
        char c = text.charAt(pos);
        if (c == '{') {
            pos++;
            return new Token(Kind.OPEN, "{");
        }
        if (c == '}') {
            pos++;
            return new Token(Kind.CLOSE, "}");
        }
        int start;
        String value;
        if (c == '"') {
            start = ++pos;
            while (pos < text.length() && text.charAt(pos) != '"') {
                if (text.charAt(pos) == '\n') throw error("unterminated quoted string");
                pos++;
                if (pos - start > limits.maxTokenLength()) throw error("string longer than " + limits.maxTokenLength());
            }
            if (pos >= text.length()) throw error("unterminated quoted string");
            value = text.substring(start, pos);
            pos++;
        } else {
            start = pos;
            while (pos < text.length()) {
                char d = text.charAt(pos);
                if (Character.isWhitespace(d) || d == '{' || d == '}' || d == '"') break;
                pos++;
                if (pos - start > limits.maxTokenLength()) throw error("word longer than " + limits.maxTokenLength());
            }
            value = text.substring(start, pos);
        }
        return new Token(Kind.STRING, value);
    }

    private void skipSpaceAndComments() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (Character.isWhitespace(c) || c == '\uFEFF') {
                pos++;
            } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                while (pos < text.length() && text.charAt(pos) != '\n') pos++;
            } else {
                return;
            }
        }
    }

    private SurfImportException error(String message) {
        return new SurfImportException("line " + line + ": " + message);
    }
}
