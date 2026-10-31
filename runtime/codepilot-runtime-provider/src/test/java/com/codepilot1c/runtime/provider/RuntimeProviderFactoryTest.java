/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.Test;

/** Tests host-vs-configuration ownership of HTTP transport policy. */
public class RuntimeProviderFactoryTest {

    @Test
    public void defaultFactoryUsesConfigurationConnectTimeout() {
        ProviderConfiguration configuration = configuration(Duration.ofSeconds(7));

        OpenAiCompatibleProvider provider = new RuntimeProviderFactory().create(configuration);

        assertEquals(Duration.ofSeconds(7), provider.httpClient().connectTimeout().orElseThrow());
    }

    @Test
    public void injectedClientOwnsConnectionPolicy() {
        HttpClient hostClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        OpenAiCompatibleProvider provider = new RuntimeProviderFactory(hostClient)
                .create(configuration(Duration.ofSeconds(7)));

        assertSame(hostClient, provider.httpClient());
        assertEquals(Duration.ofSeconds(3), provider.httpClient().connectTimeout().orElseThrow());
    }

    @Test
    public void endpointPreservesBasePathAndHandlesRootBaseUri() {
        assertEquals(URI.create("https://example.test/v1/chat/completions"), //$NON-NLS-1$
                configuration(Duration.ofSeconds(1)).chatCompletionsEndpoint());
        ProviderConfiguration root = ProviderConfiguration.builder()
                .id("root") //$NON-NLS-1$
                .displayName("Root") //$NON-NLS-1$
                .baseUri(URI.create("https://example.test/")) //$NON-NLS-1$
                .defaultModel("model") //$NON-NLS-1$
                .build();
        assertEquals(URI.create("https://example.test/chat/completions"), root.chatCompletionsEndpoint()); //$NON-NLS-1$
    }

    private static ProviderConfiguration configuration(Duration connectTimeout) {
        return ProviderConfiguration.builder()
                .id("test") //$NON-NLS-1$
                .displayName("Test") //$NON-NLS-1$
                .baseUri(URI.create("https://example.test/v1/")) //$NON-NLS-1$
                .defaultModel("model") //$NON-NLS-1$
                .connectTimeout(connectTimeout)
                .build();
    }
}
