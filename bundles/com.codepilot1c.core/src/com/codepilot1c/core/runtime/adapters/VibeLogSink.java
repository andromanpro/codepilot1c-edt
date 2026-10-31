/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.runtime.adapters;

import java.util.Objects;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.runtime.spi.LogSink;

/** Writes platform-neutral runtime events through the core logger. */
public final class VibeLogSink implements LogSink {

    @FunctionalInterface
    interface LogWriter {
        void log(VibeLogger.Level level, String category, String message, Throwable cause);
    }

    private final LogWriter writer;

    /** Creates an adapter backed by the process-wide {@link VibeLogger}. */
    public VibeLogSink() {
        this(VibeLogger.getInstance()::log);
    }

    VibeLogSink(LogWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer"); //$NON-NLS-1$
    }

    @Override
    public void log(Event event) {
        Objects.requireNonNull(event, "event"); //$NON-NLS-1$
        try {
            writer.log(toVibeLevel(event.level()), event.category(), event.message(), event.cause().orElse(null));
        } catch (RuntimeException e) {
            // Logging must never interfere with runtime control flow.
        }
    }

    private static VibeLogger.Level toVibeLevel(Level level) {
        return switch (level) {
            case DEBUG -> VibeLogger.Level.DEBUG;
            case INFO -> VibeLogger.Level.INFO;
            case WARN -> VibeLogger.Level.WARN;
            case ERROR -> VibeLogger.Level.ERROR;
        };
    }
}
