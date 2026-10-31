/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.net.http.HttpClient;
import java.util.Objects;

import com.codepilot1c.runtime.spi.ProviderFactoryRegistry.ProviderFactory;

/**
 * Creates standalone providers from platform-neutral configuration.
 *
 * <p>It is a concrete implementation of the kernel's factory shape, so a CLI
 * host can register it without importing Eclipse configuration or provider
 * classes.</p>
 */
public final class RuntimeProviderFactory implements ProviderFactory<ProviderConfiguration, OpenAiCompatibleProvider> {

    private final HttpClient httpClient;

    /**
     * Creates a factory that builds a standard Java HTTP client for each
     * provider configuration. This preserves each configuration's connection
     * timeout without introducing a global transport singleton.
     */
    public RuntimeProviderFactory() {
        this.httpClient = null;
    }

    /**
     * Creates a factory using a supplied client. This supports host-owned
     * proxy, TLS, executor, and connection-timeout policy without platform
     * APIs. The supplied client takes precedence over configuration-level
     * connection-timeout settings because Java clients are immutable.
     *
     * @param httpClient shared HTTP client
     */
    public RuntimeProviderFactory(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient"); //$NON-NLS-1$
    }

    @Override
    public OpenAiCompatibleProvider create(ProviderConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration"); //$NON-NLS-1$
        HttpClient client = httpClient != null
                ? httpClient
                : HttpClient.newBuilder()
                        .connectTimeout(configuration.connectTimeout())
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
        return new OpenAiCompatibleProvider(configuration, client);
    }
}
