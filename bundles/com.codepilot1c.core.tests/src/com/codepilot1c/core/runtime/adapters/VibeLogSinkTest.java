/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.runtime.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.runtime.spi.LogSink.Event;
import com.codepilot1c.runtime.spi.LogSink.Level;

public class VibeLogSinkTest {

    @Test
    public void mapsAllLevelsAndPreservesEventData() {
        List<CapturedLog> logs = new ArrayList<>();
        VibeLogSink sink = new VibeLogSink(
                (level, category, message, cause) -> logs.add(new CapturedLog(level, category, message, cause)));
        RuntimeException failure = new RuntimeException("expected failure"); //$NON-NLS-1$

        sink.log(Event.message(Level.DEBUG, "runtime", "debug")); //$NON-NLS-1$ //$NON-NLS-2$
        sink.log(Event.message(Level.INFO, "runtime", "info")); //$NON-NLS-1$ //$NON-NLS-2$
        sink.log(Event.message(Level.WARN, "runtime", "warn")); //$NON-NLS-1$ //$NON-NLS-2$
        sink.log(Event.failure(Level.ERROR, "runtime", "error", failure)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(
                VibeLogger.Level.DEBUG,
                VibeLogger.Level.INFO,
                VibeLogger.Level.WARN,
                VibeLogger.Level.ERROR), logs.stream().map(CapturedLog::level).toList());
        assertEquals("runtime", logs.get(3).category); //$NON-NLS-1$
        assertEquals("error", logs.get(3).message); //$NON-NLS-1$
        assertNull(logs.get(0).cause);
        assertSame(failure, logs.get(3).cause);
    }

    @Test
    public void containsLoggerFailure() {
        VibeLogSink sink = new VibeLogSink((level, category, message, cause) -> {
            throw new IllegalStateException("logger unavailable"); //$NON-NLS-1$
        });

        sink.log(Event.message(Level.INFO, "runtime", "continues")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private record CapturedLog(VibeLogger.Level level, String category, String message, Throwable cause) {
    }
}
