/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.spi;

import java.util.Optional;

/**
 * Stores credentials and other secret character data.
 *
 * <p>The SPI uses character arrays so callers can erase returned material. A
 * successful {@link #read(String)} returns a fresh array owned by the caller;
 * a successful {@link #write(String, char[])} must not retain the caller's
 * mutable array. Encryption, OS keychain integration, and prompting belong to
 * host adapters and are intentionally absent from this contract.</p>
 */
public interface SecretStore {

    /**
     * Reports whether this store can currently service secret operations.
     *
     * @return {@code true} when secret storage is available
     */
    boolean isAvailable();

    /**
     * Reads a secret into a caller-owned array.
     *
     * @param key stable namespaced key
     * @return a fresh secret array, or empty when the key is absent
     */
    Optional<char[]> read(String key);

    /**
     * Writes or replaces a secret before this method returns.
     *
     * @param key stable namespaced key
     * @param value secret characters; the store must copy them if retained
     */
    void write(String key, char[] value);

    /**
     * Removes a secret; removing an absent key is a no-op.
     *
     * @param key stable namespaced key
     */
    void remove(String key);
}
