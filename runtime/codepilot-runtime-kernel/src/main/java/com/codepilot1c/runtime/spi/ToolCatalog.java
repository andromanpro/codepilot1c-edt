/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.spi;

import java.util.List;
import java.util.Optional;

/**
 * Read-only catalog of tools visible to a runtime host.
 *
 * <p>The tool type is generic on purpose. The current production tool
 * execution contract has not been migrated, and duplicating its schema,
 * permission, or asynchronous execution model here would freeze an unreviewed
 * API. An adapter may therefore expose the existing tool type while a future
 * standalone implementation supplies its own platform-neutral type.</p>
 *
 * @param <T> host-specific tool contract
 */
public interface ToolCatalog<T> {

    /**
     * Returns a stable, unmodifiable snapshot in catalog order.
     *
     * @return current tool snapshot
     */
    List<T> snapshot();

    /**
     * Finds a tool by its externally visible name.
     *
     * @param name exact tool name
     * @return matching tool, or empty when it is not visible
     */
    Optional<T> find(String name);
}
