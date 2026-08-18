/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.net.URI;
import java.util.Optional;

/** Independent MCP endpoint snapshot; an empty endpoint means MCP is disabled. */
public record McpRuntimeSettings(Optional<URI> endpoint, ConfigurationSource source) {
    public McpRuntimeSettings {
        endpoint = endpoint == null ? Optional.empty() : endpoint;
        if (source == null) throw new NullPointerException("source"); //$NON-NLS-1$
    }
}
