/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.render;

import java.util.Iterator;
import java.util.Map;

/** Small deterministic JSON encoder for CLI-owned scalar/list/map payloads. */
public final class JsonWriter {
    private JsonWriter() { }

    public static String write(Object value) {
        StringBuilder result = new StringBuilder();
        append(result, value);
        return result.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String text) { string(out, text); return; }
        if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                string(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
                if (entries.hasNext()) out.append(',');
            }
            out.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            out.append('[');
            Iterator<?> elements = iterable.iterator();
            while (elements.hasNext()) {
                append(out, elements.next());
                if (elements.hasNext()) out.append(',');
            }
            out.append(']');
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\b' -> out.append("\\b");
            case '\f' -> out.append("\\f");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
                if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
                else out.append(character);
            }
            }
        }
        out.append('"');
    }
}
