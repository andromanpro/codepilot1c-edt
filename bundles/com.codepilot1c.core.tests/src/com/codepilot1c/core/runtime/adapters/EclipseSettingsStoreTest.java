/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.runtime.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.Test;
import org.osgi.service.prefs.BackingStoreException;

public class EclipseSettingsStoreTest {

    @Test
    public void readsWritesRemovesAndFlushesPreferences() {
        PreferenceNode node = new PreferenceNode();
        EclipseSettingsStore store = new EclipseSettingsStore(node.proxy());

        assertEquals(Optional.empty(), store.read("runtime.model")); //$NON-NLS-1$
        store.write("runtime.model", "model-a"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Optional.of("model-a"), store.read("runtime.model")); //$NON-NLS-1$ //$NON-NLS-2$
        store.remove("runtime.model"); //$NON-NLS-1$

        assertEquals(Optional.empty(), store.read("runtime.model")); //$NON-NLS-1$
        assertEquals(2, node.flushCount);
    }

    @Test
    public void reportsFlushFailuresAsUncheckedStorageFailures() {
        PreferenceNode node = new PreferenceNode();
        node.failFlush = true;
        EclipseSettingsStore store = new EclipseSettingsStore(node.proxy());

        assertThrows(IllegalStateException.class, () -> store.write("runtime.model", "model-a")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class PreferenceNode {
        private final Map<String, String> values = new HashMap<>();
        private int flushCount;
        private boolean failFlush;

        private IEclipsePreferences proxy() {
            return (IEclipsePreferences) Proxy.newProxyInstance(
                    IEclipsePreferences.class.getClassLoader(),
                    new Class<?>[] { IEclipsePreferences.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> values.getOrDefault((String) args[0], (String) args[1]); //$NON-NLS-1$
                        case "put" -> {
                            values.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "remove" -> {
                            values.remove((String) args[0]);
                            yield null;
                        }
                        case "flush" -> {
                            flushCount++;
                            if (failFlush) {
                                throw new BackingStoreException("flush failed"); //$NON-NLS-1$
                            }
                            yield null;
                        }
                        case "toString" -> "PreferenceNode"; //$NON-NLS-1$ //$NON-NLS-2$
                        case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
                        case "equals" -> proxy == args[0]; //$NON-NLS-1$
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
