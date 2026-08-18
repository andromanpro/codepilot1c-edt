/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.spi;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers provider factories by provider-neutral type identifier.
 *
 * <p>Both configuration and provider types remain generic because production
 * provider classes are not part of this migration slice. The registry only
 * provides discovery; active-provider selection, configuration persistence,
 * factory lifecycle, and concrete provider identifiers belong to host
 * composition.</p>
 *
 * @param <C> provider configuration accepted by registered factories
 * @param <P> provider contract produced by registered factories
 */
public interface ProviderFactoryRegistry<C, P> {

    /**
     * Returns an unmodifiable snapshot of registered provider types.
     *
     * @return registered type identifiers
     */
    Set<ProviderTypeId> types();

    /**
     * Finds the factory registered for a provider type.
     *
     * @param type provider-neutral type identifier
     * @return factory, or empty when that type is not registered
     */
    Optional<ProviderFactory<C, P>> find(ProviderTypeId type);

    /**
     * Creates a provider from host-owned configuration.
     *
     * @param <C> configuration type
     * @param <P> provider type
     */
    @FunctionalInterface
    interface ProviderFactory<C, P> {

        /**
         * Creates a provider instance.
         *
         * @param configuration provider configuration
         * @return new provider instance
         */
        P create(C configuration);
    }

    /**
     * Stable provider type identifier. No concrete identifiers are defined by
     * the kernel.
     *
     * @param value non-blank, whitespace-trimmed identifier
     */
    record ProviderTypeId(String value) {

        /**
         * Validates the identifier without applying provider-specific rules.
         *
         * @param value provider type identifier
         */
        public ProviderTypeId {
            Objects.requireNonNull(value, "value"); //$NON-NLS-1$
            if (value.isBlank()) {
                throw new IllegalArgumentException("value must not be blank"); //$NON-NLS-1$
            }
            if (!value.equals(value.trim())) {
                throw new IllegalArgumentException("value must not have surrounding whitespace"); //$NON-NLS-1$
            }
        }
    }
}
