/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** Mutable shell-lifetime exact-secret redactor; all private copies are wiped on close. */
public final class ShellSecretRedactor implements UnaryOperator<String>, AutoCloseable {
    private final Object lock = new Object();
    private final List<char[]> secrets = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public void add(char[] value) {
        if (value == null || value.length == 0) return;
        synchronized (lock) {
            if (closed.get()) throw new IllegalStateException("redactor is closed");
            for (char[] existing : secrets) if (Arrays.equals(existing, value)) return;
            secrets.add(value.clone());
            secrets.sort(Comparator.comparingInt((char[] item) -> item.length).reversed());
        }
    }

    @Override public String apply(String value) {
        if (value == null || value.isEmpty()) return value;
        synchronized (lock) {
            String result = value;
            for (char[] secret : secrets) result = replace(result, secret);
            return result;
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lock) {
            secrets.forEach(value -> Arrays.fill(value, '\0'));
            secrets.clear();
        }
    }

    private static String replace(String source, char[] secret) {
        StringBuilder output = null;
        int copied = 0;
        for (int offset = 0; offset <= source.length() - secret.length;) {
            if (!matches(source, offset, secret)) {
                offset++;
                continue;
            }
            if (output == null) output = new StringBuilder(source.length());
            output.append(source, copied, offset).append("<redacted>");
            offset += secret.length;
            copied = offset;
        }
        return output == null ? source : output.append(source, copied, source.length()).toString();
    }

    private static boolean matches(String value, int offset, char[] secret) {
        for (int index = 0; index < secret.length; index++) {
            if (value.charAt(offset + index) != secret[index]) return false;
        }
        return true;
    }
}
