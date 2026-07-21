/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import java.util.Optional;

import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;

/**
 * Resolves the active provider configuration for diagnostics that inspect the active model.
 */
public final class ActiveProviderConfigResolver {

    /**
     * Resolves the active provider configuration.
     *
     * @return a defensive config copy, or an empty config when none is available
     */
    public LlmProviderConfig resolve() {
        try {
            LlmProviderRegistry registry = LlmProviderRegistry.getInstance();
            LlmProviderConfigStore store = registry.getConfigStore();
            if (store == null) {
                store = LlmProviderConfigStore.getInstance();
            }
            String activeProviderId = store.getActiveProviderId();
            if (activeProviderId != null && !activeProviderId.isBlank()) {
                if ("backend".equals(activeProviderId) //$NON-NLS-1$
                        && registry.getBackendProvider() instanceof DynamicLlmProvider backend) {
                    return backend.getConfig().copy();
                }
                Optional<LlmProviderConfig> active = store.getProvider(activeProviderId);
                if (active.isPresent()) {
                    return active.get().copy();
                }
            }
            return store.getProviders().stream()
                    .filter(LlmProviderConfig::isConfigured)
                    .findFirst()
                    .map(LlmProviderConfig::copy)
                    .orElseGet(LlmProviderConfig::new);
        } catch (RuntimeException e) {
            return new LlmProviderConfig();
        }
    }
}
