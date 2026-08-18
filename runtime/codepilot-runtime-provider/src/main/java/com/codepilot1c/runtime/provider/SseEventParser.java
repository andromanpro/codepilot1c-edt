/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.util.Objects;
import java.util.function.Consumer;

/** Incremental, dependency-free SSE line and frame parser. */
final class SseEventParser {

    record Event(String type, String data) {
    }

    private final Consumer<Event> consumer;
    private final StringBuilder line = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private String eventType = "message"; //$NON-NLS-1$
    private boolean hasData;
    private boolean pendingCarriageReturn;

    SseEventParser(Consumer<Event> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer"); //$NON-NLS-1$
    }

    void accept(char[] characters, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, characters.length);
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            char current = characters[index];
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false;
                endLine();
                if (current == '\n') continue;
            }
            if (current == '\r') pendingCarriageReturn = true;
            else if (current == '\n') endLine();
            else line.append(current);
        }
    }

    /** Dispatches a final unterminated line/frame when the response reaches EOF. */
    void finish() {
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            endLine();
        } else if (line.length() > 0) {
            consumeLine();
        }
        dispatch();
    }

    private void endLine() {
        if (line.length() == 0) dispatch();
        else consumeLine();
    }

    private void consumeLine() {
        String value = line.toString();
        line.setLength(0);
        if (value.charAt(0) == ':') return;

        int separator = value.indexOf(':');
        String field = separator < 0 ? value : value.substring(0, separator);
        String fieldValue = separator < 0 ? "" : value.substring(separator + 1); //$NON-NLS-1$
        if (fieldValue.startsWith(" ")) fieldValue = fieldValue.substring(1); //$NON-NLS-1$
        if ("event".equals(field)) { //$NON-NLS-1$
            eventType = fieldValue.isEmpty() ? "message" : fieldValue; //$NON-NLS-1$
        } else if ("data".equals(field)) { //$NON-NLS-1$
            data.append(fieldValue).append('\n');
            hasData = true;
        }
    }

    private void dispatch() {
        if (hasData) {
            data.setLength(data.length() - 1);
            consumer.accept(new Event(eventType, data.toString()));
        }
        data.setLength(0);
        hasData = false;
        eventType = "message"; //$NON-NLS-1$
    }
}
