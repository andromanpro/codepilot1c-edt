/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * Receives platform-neutral runtime log events.
 *
 * <p>The contract intentionally does not expose Eclipse log statuses, SLF4J,
 * or a UI logger. A host adapter decides where events are written. Sink
 * implementations should not let logging failures escape into runtime
 * control flow.</p>
 */
@FunctionalInterface
public interface LogSink {

    /**
     * Writes one log event.
     *
     * @param event immutable event to write
     */
    void log(Event event);

    /** Severity understood by the platform-neutral runtime. */
    enum Level {
        /** Diagnostic detail. */
        DEBUG,
        /** Normal runtime progress. */
        INFO,
        /** Recoverable or degraded behavior. */
        WARN,
        /** Failed behavior requiring attention. */
        ERROR
    }

    /**
     * Immutable log payload without a platform-specific timestamp or status
     * object. The sink may attach its own timestamp when it accepts the event.
     *
     * @param level event severity
     * @param category stable runtime component or subsystem name
     * @param message human-readable message
     * @param cause optional failure associated with the event
     */
    record Event(Level level, String category, String message, Optional<Throwable> cause) {

        /**
         * Validates required event fields.
         *
         * @param level event severity
         * @param category component or subsystem name
         * @param message human-readable message
         * @param cause optional associated failure
         */
        public Event {
            Objects.requireNonNull(level, "level"); //$NON-NLS-1$
            category = requireText(category, "category"); //$NON-NLS-1$
            message = requireText(message, "message"); //$NON-NLS-1$
            Objects.requireNonNull(cause, "cause"); //$NON-NLS-1$
        }

        /**
         * Creates an event without an associated failure.
         *
         * @param level event severity
         * @param category component or subsystem name
         * @param message human-readable message
         * @return validated event
         */
        public static Event message(Level level, String category, String message) {
            return new Event(level, category, message, Optional.empty());
        }

        /**
         * Creates an event with an associated failure.
         *
         * @param level event severity
         * @param category component or subsystem name
         * @param message human-readable message
         * @param cause associated failure
         * @return validated event
         */
        public static Event failure(Level level, String category, String message, Throwable cause) {
            return new Event(level, category, message, Optional.of(Objects.requireNonNull(cause, "cause"))); //$NON-NLS-1$
        }

        private static String requireText(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank"); //$NON-NLS-1$
            }
            return value;
        }
    }
}
