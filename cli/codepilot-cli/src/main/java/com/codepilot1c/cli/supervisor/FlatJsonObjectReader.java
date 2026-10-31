/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict reader for the flat JSON instance-registry schema with additive scalar arrays. */
final class FlatJsonObjectReader {
    private final String input;
    private int position;

    private FlatJsonObjectReader(String input) { this.input = input; }

    static Map<String, Object> read(String input) {
        FlatJsonObjectReader reader = new FlatJsonObjectReader(input);
        Map<String, Object> result = reader.object();
        reader.space();
        if (reader.position != reader.input.length()) throw reader.error("trailing content");
        return result;
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        space();
        if (take('}')) return result;
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            space();
            Object value = value();
            if (result.containsKey(key)) throw error("duplicate key");
            result.put(key, value);
            space();
            if (take('}')) return result;
            expect(',');
        }
    }

    private Object value() {
        return peek('[') ? array() : scalar();
    }

    private List<Object> array() {
        expect('[');
        List<Object> result = new ArrayList<>();
        space();
        if (take(']')) return List.of();
        while (true) {
            space();
            result.add(scalar());
            space();
            if (take(']')) return Collections.unmodifiableList(result);
            expect(',');
        }
    }

    private Object scalar() {
        if (peek('"')) return string();
        if (match("null")) return null;
        if (match("true")) return Boolean.TRUE;
        if (match("false")) return Boolean.FALSE;
        int start = position;
        if (take('-')) { }
        while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
        if (position == start || (position == start + 1 && input.charAt(start) == '-')) throw error("scalar expected");
        try { return Long.parseLong(input.substring(start, position)); }
        catch (NumberFormatException exception) { throw error("invalid integer"); }
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (position < input.length()) {
            char character = input.charAt(position++);
            if (character == '"') return value.toString();
            if (character != '\\') {
                if (character < 0x20) throw error("control character in string");
                value.append(character);
                continue;
            }
            if (position >= input.length()) throw error("unterminated escape");
            char escaped = input.charAt(position++);
            switch (escaped) {
            case '"', '\\', '/' -> value.append(escaped);
            case 'b' -> value.append('\b');
            case 'f' -> value.append('\f');
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 't' -> value.append('\t');
            case 'u' -> value.append(unicode());
            default -> throw error("invalid escape");
            }
        }
        throw error("unterminated string");
    }

    private char unicode() {
        if (position + 4 > input.length()) throw error("short unicode escape");
        try {
            char value = (char) Integer.parseInt(input.substring(position, position + 4), 16);
            position += 4;
            return value;
        } catch (NumberFormatException exception) {
            throw error("invalid unicode escape");
        }
    }

    private void expect(char expected) {
        space();
        if (!take(expected)) throw error("expected '" + expected + "'");
    }

    private boolean take(char expected) {
        if (position < input.length() && input.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private boolean peek(char value) { return position < input.length() && input.charAt(position) == value; }

    private boolean match(String value) {
        if (!input.startsWith(value, position)) return false;
        position += value.length();
        return true;
    }

    private void space() {
        while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + position);
    }
}
