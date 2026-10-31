/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.runtime.adapters;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class EclipseSecretStoreTest {

    @Test
    public void copiesWrittenAndReadSecretArrays() {
        MemorySecureStorage backend = new MemorySecureStorage();
        EclipseSecretStore store = new EclipseSecretStore(backend);
        char[] callerValue = "secret-value".toCharArray(); //$NON-NLS-1$

        store.write("runtime.token", callerValue); //$NON-NLS-1$
        callerValue[0] = 'X';
        char[] firstRead = store.read("runtime.token").orElseThrow(); //$NON-NLS-1$
        firstRead[1] = 'X';
        char[] secondRead = store.read("runtime.token").orElseThrow(); //$NON-NLS-1$

        assertArrayEquals("secret-value".toCharArray(), secondRead); //$NON-NLS-1$
        assertFalse(firstRead == secondRead);
    }

    @Test
    public void delegatesAvailabilityAndRemoval() {
        MemorySecureStorage backend = new MemorySecureStorage();
        EclipseSecretStore store = new EclipseSecretStore(backend);
        assertTrue(store.isAvailable());
        assertTrue(store.read("missing").isEmpty()); //$NON-NLS-1$

        store.write("runtime.token", "value".toCharArray()); //$NON-NLS-1$ //$NON-NLS-2$
        store.remove("runtime.token"); //$NON-NLS-1$

        assertTrue(store.read("runtime.token").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void reportsSecureWriteFailureWithoutIncludingSecret() {
        MemorySecureStorage backend = new MemorySecureStorage();
        backend.acceptWrites = false;
        EclipseSecretStore store = new EclipseSecretStore(backend);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.write("runtime.token", "do-not-expose".toCharArray())); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(-1, failure.getMessage().indexOf("do-not-expose")); //$NON-NLS-1$
    }

    private static final class MemorySecureStorage implements EclipseSecretStore.SecureStorage {
        private final Map<String, String> values = new HashMap<>();
        private boolean acceptWrites = true;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String read(String key) {
            return values.get(key);
        }

        @Override
        public boolean write(String key, String value) {
            if (acceptWrites) {
                values.put(key, value);
            }
            return acceptWrites;
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
