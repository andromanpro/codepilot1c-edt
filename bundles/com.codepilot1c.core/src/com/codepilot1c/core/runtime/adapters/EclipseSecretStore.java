/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.runtime.adapters;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.codepilot1c.core.settings.SecureStorageUtil;
import com.codepilot1c.runtime.spi.SecretStore;

/** Stores runtime secrets through Eclipse secure storage. */
public final class EclipseSecretStore implements SecretStore {

    interface SecureStorage {
        boolean isAvailable();

        String read(String key);

        boolean write(String key, String value);

        void remove(String key);
    }

    private static final SecureStorage ECLIPSE_STORAGE = new SecureStorage() {
        @Override
        public boolean isAvailable() {
            return SecureStorageUtil.isAvailable();
        }

        @Override
        public String read(String key) {
            return SecureStorageUtil.retrieveSecurely(key, null);
        }

        @Override
        public boolean write(String key, String value) {
            return SecureStorageUtil.storeSecurely(key, value);
        }

        @Override
        public void remove(String key) {
            SecureStorageUtil.removeSecurely(key);
        }
    };

    private final SecureStorage storage;

    /** Creates a store backed by {@link SecureStorageUtil}. */
    public EclipseSecretStore() {
        this(ECLIPSE_STORAGE);
    }

    EclipseSecretStore(SecureStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage"); //$NON-NLS-1$
    }

    @Override
    public boolean isAvailable() {
        return storage.isAvailable();
    }

    @Override
    public Optional<char[]> read(String key) {
        String value = storage.read(requireKey(key));
        return value == null ? Optional.empty() : Optional.of(value.toCharArray());
    }

    @Override
    public void write(String key, char[] value) {
        requireKey(key);
        Objects.requireNonNull(value, "value"); //$NON-NLS-1$
        char[] ownedCopy = Arrays.copyOf(value, value.length);
        try {
            if (!storage.write(key, new String(ownedCopy))) {
                throw new IllegalStateException("Failed to store runtime secret for key: " + key); //$NON-NLS-1$
            }
        } finally {
            Arrays.fill(ownedCopy, '\0');
        }
    }

    @Override
    public void remove(String key) {
        storage.remove(requireKey(key));
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "key"); //$NON-NLS-1$
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank"); //$NON-NLS-1$
        }
        return key;
    }
}
