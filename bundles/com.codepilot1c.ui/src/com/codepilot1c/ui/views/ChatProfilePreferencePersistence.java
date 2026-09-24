/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.Objects;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.osgi.service.prefs.BackingStoreException;

/** Durable preference boundary for the shared ChatView profile default. */
public final class ChatProfilePreferencePersistence {

    /** Minimal preference seam used by production and deterministic tests. */
    public interface Store {
        String get(String key, String defaultValue);

        void put(String key, String value);

        void flush() throws BackingStoreException;
    }

    /** Checked persistence failure context for structured UI-owned logging. */
    public record Failure(
            String opId,
            String key,
            String value,
            BackingStoreException cause) {
    }

    @FunctionalInterface
    public interface FailureReporter {
        void report(Failure failure);
    }

    private ChatProfilePreferencePersistence() {
    }

    /** Adapts an Eclipse preference node without changing its scope or owner. */
    public static Store eclipseStore(IEclipsePreferences preferences) {
        Objects.requireNonNull(preferences, "preferences"); //$NON-NLS-1$
        return new Store() {
            @Override
            public String get(String key, String defaultValue) {
                return preferences.get(key, defaultValue);
            }

            @Override
            public void put(String key, String value) {
                preferences.put(key, value);
            }

            @Override
            public void flush() throws BackingStoreException {
                preferences.flush();
            }
        };
    }

    /**
     * Writes and durably flushes a preference. A checked flush failure is
     * reported and converted to {@code false} so it cannot terminate the UI
     * selection/fallback flow.
     */
    public static boolean putAndFlush(
            Store store,
            String key,
            String value,
            String opId,
            FailureReporter failureReporter) {
        Objects.requireNonNull(store, "store"); //$NON-NLS-1$
        Objects.requireNonNull(key, "key"); //$NON-NLS-1$
        Objects.requireNonNull(value, "value"); //$NON-NLS-1$
        Objects.requireNonNull(opId, "opId"); //$NON-NLS-1$
        Objects.requireNonNull(failureReporter, "failureReporter"); //$NON-NLS-1$

        store.put(key, value);
        try {
            store.flush();
            return true;
        } catch (BackingStoreException e) {
            failureReporter.report(new Failure(opId, key, value, e));
            return false;
        }
    }

    public static String get(Store store, String key, String defaultValue) {
        Objects.requireNonNull(store, "store"); //$NON-NLS-1$
        return store.get(key, defaultValue);
    }
}
