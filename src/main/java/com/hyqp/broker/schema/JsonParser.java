package com.hyqp.broker.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser. Zero dependencies.
 * Parses JSON text into JsonValue instances.
 */
public final class JsonParser {

    private final String input;
    private int pos;

    private JsonParser(String input) {
        this.input = input;
        this.pos = 0;
    }

    public static JsonValue parse(String json) {
        if (json == null || json.isBlank()) {
            throw new JsonParseException("Empty input");
        }
        JsonParser parser = new JsonParser(json.strip());
        JsonValue value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < parser.input.length()) {
            throw new JsonParseException("Unexpected trailing content at position " + parser.pos);
        }
        return value;
    }

    private JsonValue readValue() {
        skipWhitespace();
        if (pos >= input.length()) throw new JsonParseException("Unexpected end of input");

        char c = input.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw new JsonParseException("Unexpected character '" + c + "' at position " + pos);
            }
        };
    }

    private JsonValue readObject() {
        expect('{');
        Map<String, JsonValue> map = new LinkedHashMap<>();
        skipWhitespace();

        if (pos < input.length() && input.charAt(pos) == '}') {
            pos++;
            return new JsonValue(map);
        }

        while (true) {
            skipWhitespace();
            String key = readString().asString();
            skipWhitespace();
            expect(':');
            JsonValue value = readValue();
            map.put(key, value);
            skipWhitespace();

            if (pos >= input.length()) throw new JsonParseException("Unterminated object");
            char c = input.charAt(pos);
            if (c == '}') { pos++; break; }
            if (c == ',') { pos++; continue; }
            throw new JsonParseException("Expected ',' or '}' at position " + pos);
        }

        return new JsonValue(map);
    }

    private JsonValue readArray() {
        expect('[');
        List<JsonValue> list = new ArrayList<>();
        skipWhitespace();

        if (pos < input.length() && input.charAt(pos) == ']') {
            pos++;
            return new JsonValue(list);
        }

        while (true) {
            list.add(readValue());
            skipWhitespace();

            if (pos >= input.length()) throw new JsonParseException("Unterminated array");
            char c = input.charAt(pos);
            if (c == ']') { pos++; break; }
            if (c == ',') { pos++; continue; }
            throw new JsonParseException("Expected ',' or ']' at position " + pos);
        }

        return new JsonValue(list);
    }

    private JsonValue readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') return new JsonValue(sb.toString());
            if (c == '\\') {
                if (pos >= input.length()) throw new JsonParseException("Unterminated escape");
                char esc = input.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > input.length()) throw new JsonParseException("Invalid unicode escape");
                        String hex = input.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw new JsonParseException("Invalid escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new JsonParseException("Unterminated string");
    }

    private JsonValue readNumber() {
        int start = pos;
        if (pos < input.length() && input.charAt(pos) == '-') pos++;
        while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
        }
        String numStr = input.substring(start, pos);
        return new JsonValue(Double.parseDouble(numStr));
    }

    private JsonValue readBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return new JsonValue(true);
        }
        if (input.startsWith("false", pos)) {
            pos += 5;
            return new JsonValue(false);
        }
        throw new JsonParseException("Invalid boolean at position " + pos);
    }

    private JsonValue readNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return JsonValue.NULL;
        }
        throw new JsonParseException("Invalid null at position " + pos);
    }

    private void expect(char expected) {
        skipWhitespace();
        if (pos >= input.length() || input.charAt(pos) != expected) {
            throw new JsonParseException("Expected '" + expected + "' at position " + pos);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }
}
