package dev.csmc.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;

/** Parser for exported KV3 text, not Source 2's binary KV3 encoding. */
public final class Kv3Parser {
    private final String text;
    private int cursor;

    private Kv3Parser(String text) {
        this.text = text;
    }

    public static JsonObject parse(String text) {
        Kv3Parser parser = new Kv3Parser(text);
        JsonElement value = parser.value();
        parser.whitespace();
        if (parser.cursor != text.length() || !value.isJsonObject()) {
            throw parser.error("Expected one root object");
        }
        return value.getAsJsonObject();
    }

    private JsonElement value() {
        whitespace();
        if (cursor >= text.length()) {
            throw error("Expected value");
        }
        char c = text.charAt(cursor);
        if (c == '{') {
            cursor++;
            JsonObject object = new JsonObject();
            while (!take('}')) {
                String key = token();
                expect('=');
                object.add(key, value());
                take(',');
                take(';');
            }
            return object;
        }
        if (c == '[') {
            cursor++;
            JsonArray array = new JsonArray();
            while (!take(']')) {
                array.add(value());
                take(',');
            }
            return array;
        }
        if (c == '"') {
            return new JsonPrimitive(quoted());
        }
        String atom = token();
        if (take(':')) {
            // KV3 flags (resource_name:, soundevent:, etc.) decorate a normal value.
            return value();
        }
        return switch (atom) {
            case "true" -> new JsonPrimitive(true);
            case "false" -> new JsonPrimitive(false);
            case "null" -> JsonNull.INSTANCE;
            default -> {
                try {
                    yield new JsonPrimitive(new BigDecimal(atom));
                } catch (NumberFormatException e) {
                    yield new JsonPrimitive(atom);
                }
            }
        };
    }

    private String token() {
        whitespace();
        if (cursor < text.length() && text.charAt(cursor) == '"') {
            return quoted();
        }
        int start = cursor;
        while (cursor < text.length()) {
            char c = text.charAt(cursor);
            if (Character.isWhitespace(c) || "{}[]=,:;".indexOf(c) >= 0) {
                break;
            }
            cursor++;
        }
        if (start == cursor) {
            throw error("Expected token");
        }
        return text.substring(start, cursor);
    }

    private String quoted() {
        if (text.startsWith("\"\"\"", cursor)) {
            cursor += 3;
            int end = text.indexOf("\"\"\"", cursor);
            if (end < 0) {
                throw error("Unterminated multiline string");
            }
            String result = text.substring(cursor, end);
            cursor = end + 3;
            return result;
        }
        cursor++;
        StringBuilder result = new StringBuilder();
        while (cursor < text.length()) {
            char c = text.charAt(cursor++);
            if (c == '"') {
                return result.toString();
            }
            if (c == '\\') {
                if (cursor == text.length()) {
                    throw error("Unterminated string escape");
                }
                char escaped = text.charAt(cursor++);
                switch (escaped) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case '\\', '"', '/' -> result.append(escaped);
                    case 'u' -> {
                        if (cursor + 4 > text.length()) {
                            throw error("Incomplete Unicode escape");
                        }
                        try {
                            result.append((char) Integer.parseInt(text.substring(cursor, cursor + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid Unicode escape");
                        }
                        cursor += 4;
                    }
                    default -> throw error("Unknown string escape");
                }
            } else {
                result.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private void whitespace() {
        while (cursor < text.length()) {
            if (Character.isWhitespace(text.charAt(cursor)) || text.charAt(cursor) == '\uFEFF') {
                cursor++;
            } else if (text.startsWith("<!--", cursor)) {
                skipComment("-->", 4);
            } else if (text.startsWith("/*", cursor)) {
                skipComment("*/", 2);
            } else if (text.startsWith("//", cursor)) {
                int end = text.indexOf('\n', cursor + 2);
                cursor = end < 0 ? text.length() : end + 1;
            } else {
                return;
            }
        }
    }

    private void skipComment(String close, int openingLength) {
        int end = text.indexOf(close, cursor + openingLength);
        if (end < 0) {
            throw error("Unterminated comment");
        }
        cursor = end + close.length();
    }

    private boolean take(char expected) {
        whitespace();
        if (cursor < text.length() && text.charAt(cursor) == expected) {
            cursor++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!take(expected)) {
            throw error("Expected '" + expected + "'");
        }
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message + " at character " + cursor);
    }
}
