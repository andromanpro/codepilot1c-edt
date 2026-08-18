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
 * Stores non-secret runtime settings as opaque string values.
 *
 * <p>Serialization is deliberately owned by the caller so this SPI does not
 * commit the kernel to JSON, Eclipse preferences, or a CLI configuration-file
 * format. Keys should be stable and namespaced by their owner. Implementations
 * must distinguish a missing value from a storage failure; failures should be
 * reported as implementation-specific unchecked exceptions.</p>
 */
public interface SettingsStore {

    /**
     * Reads a setting.
     *
     * @param key stable namespaced key
     * @return stored value, or empty when the key is absent
     */
    Optional<String> read(String key);

    /**
     * Writes or replaces a setting before this method returns.
     *
     * @param key stable namespaced key
     * @param value opaque serialized value
     */
    void write(String key, String value);

    /**
     * Removes a setting; removing an absent key is a no-op.
     *
     * @param key stable namespaced key
     */
    void remove(String key);
}
