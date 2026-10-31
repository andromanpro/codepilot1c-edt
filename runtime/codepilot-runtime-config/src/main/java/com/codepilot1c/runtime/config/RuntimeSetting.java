/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

/** Public names of the ordinary, non-secret runtime settings. */
public enum RuntimeSetting {
    PROVIDER_BASE_URI("provider.baseUri"), //$NON-NLS-1$
    PROVIDER_MODEL("provider.model"), //$NON-NLS-1$
    PROVIDER_CONNECT_TIMEOUT_MILLIS("provider.connectTimeoutMillis"), //$NON-NLS-1$
    PROVIDER_REQUEST_TIMEOUT_MILLIS("provider.requestTimeoutMillis"), //$NON-NLS-1$
    MCP_ENDPOINT("mcp.endpoint"), //$NON-NLS-1$
    AGENT_MAX_STEPS("agent.maxSteps"), //$NON-NLS-1$
    AGENT_TIMEOUT_MILLIS("agent.timeoutMillis"), //$NON-NLS-1$
    PROVIDER_API_KEY_FILE("provider.apiKeyFile"); //$NON-NLS-1$

    private final String key;

    RuntimeSetting(String key) {
        this.key = key;
    }

    /** @return strict external key name */
    public String key() {
        return key;
    }

    String propertyName() {
        return "codepilot." + key; //$NON-NLS-1$
    }

    String environmentName() {
        return "CODEPILOT_" + key.replaceAll("([a-z])([A-Z])", "$1_$2") //$NON-NLS-1$ //$NON-NLS-2$
                .replace('.', '_').toUpperCase(java.util.Locale.ROOT);
    }
}
