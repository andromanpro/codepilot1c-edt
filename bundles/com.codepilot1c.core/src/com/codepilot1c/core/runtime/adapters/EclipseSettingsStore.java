/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.runtime.adapters;

import java.util.Objects;
import java.util.Optional;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.runtime.spi.SettingsStore;

/** Persists non-secret runtime settings in Eclipse instance preferences. */
public final class EclipseSettingsStore implements SettingsStore {

    private final IEclipsePreferences preferences;

    /** Creates a store in the core plug-in's instance preference node. */
    public EclipseSettingsStore() {
        this(InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID));
    }

    /**
     * Creates a store over an explicit preference node.
     *
     * @param preferences preference node owned by the caller
     */
    public EclipseSettingsStore(IEclipsePreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences"); //$NON-NLS-1$
    }

    @Override
    public Optional<String> read(String key) {
        return Optional.ofNullable(preferences.get(requireKey(key), null));
    }

    @Override
    public void write(String key, String value) {
        preferences.put(requireKey(key), Objects.requireNonNull(value, "value")); //$NON-NLS-1$
        flush();
    }

    @Override
    public void remove(String key) {
        preferences.remove(requireKey(key));
        flush();
    }

    private void flush() {
        try {
            preferences.flush();
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Failed to flush runtime settings", e); //$NON-NLS-1$
        }
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "key"); //$NON-NLS-1$
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank"); //$NON-NLS-1$
        }
        return key;
    }
}
