/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Redacts only exact CLI-configured secrets and wipes its private copies on close. */
final class ExactSecretRedactor implements AutoCloseable {
    private static final String REDACTED = "<redacted>";
    private char[][] secrets;

    private ExactSecretRedactor(List<char[]> values) {
        List<char[]> copies = new ArrayList<>();
        try {
            for (char[] value : values) {
                if (value == null || value.length == 0 || contains(copies, value)) continue;
                copies.add(value.clone());
            }
            copies.sort(Comparator.comparingInt((char[] value) -> value.length).reversed());
            secrets = copies.toArray(char[][]::new);
        } catch (RuntimeException | Error failure) {
            copies.forEach(value -> Arrays.fill(value, '\0'));
            throw failure;
        }
    }

    static ExactSecretRedactor of(char[]... values) {
        return new ExactSecretRedactor(values == null ? List.of() : Arrays.asList(values));
    }

    static ExactSecretRedactor combine(ExactSecretRedactor first, ExactSecretRedactor second) {
        List<char[]> values = new ArrayList<>();
        first.copyInto(values);
        second.copyInto(values);
        try {
            return new ExactSecretRedactor(values);
        } finally {
            values.forEach(value -> Arrays.fill(value, '\0'));
        }
    }

    String redact(String value) {
        if (value == null || value.isEmpty() || secrets.length == 0) return value;
        StringBuilder output = null;
        int copiedUntil = 0;
        for (int index = 0; index < value.length();) {
            char[] match = matchingSecret(value, index);
            if (match == null) {
                index++;
                continue;
            }
            if (output == null) output = new StringBuilder(value.length());
            output.append(value, copiedUntil, index).append(REDACTED);
            index += match.length;
            copiedUntil = index;
        }
        if (output == null) return value;
        return output.append(value, copiedUntil, value.length()).toString();
    }

    Object redact(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String text) return redact(text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                output.put(redact(String.valueOf(entry.getKey())), redact(entry.getValue()));
            }
            return output;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> output = new ArrayList<>();
            for (Object item : iterable) output.add(redact(item));
            return output;
        }
        throw new IllegalArgumentException("Unsupported output value: " + value.getClass().getName());
    }

    @Override public void close() {
        char[][] current = secrets;
        secrets = new char[0][];
        for (char[] secret : current) Arrays.fill(secret, '\0');
    }

    private char[] matchingSecret(String value, int offset) {
        for (char[] secret : secrets) {
            if (offset + secret.length > value.length()) continue;
            int index = 0;
            while (index < secret.length && value.charAt(offset + index) == secret[index]) index++;
            if (index == secret.length) return secret;
        }
        return null;
    }

    private void copyInto(List<char[]> output) {
        for (char[] secret : secrets) output.add(secret.clone());
    }

    private static boolean contains(List<char[]> values, char[] candidate) {
        for (char[] value : values) if (Arrays.equals(value, candidate)) return true;
        return false;
    }
}
