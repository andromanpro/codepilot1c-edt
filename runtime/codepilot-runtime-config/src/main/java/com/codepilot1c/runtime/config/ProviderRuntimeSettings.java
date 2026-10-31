/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/** Independent provider-facing snapshot for a host integration adapter. */
public final class ProviderRuntimeSettings implements AutoCloseable {
    private final URI baseUri;
    private final String model;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private char[] apiKey;
    private boolean closed;

    ProviderRuntimeSettings(URI baseUri, String model, Duration connectTimeout, Duration requestTimeout, char[] apiKey) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri"); //$NON-NLS-1$
        this.model = Objects.requireNonNull(model, "model"); //$NON-NLS-1$
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout"); //$NON-NLS-1$
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout"); //$NON-NLS-1$
        this.apiKey = apiKey == null ? new char[0] : apiKey.clone();
    }

    public URI baseUri() { return baseUri; }
    public String model() { return model; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration requestTimeout() { return requestTimeout; }
    public synchronized boolean hasApiKey() { return !closed && apiKey.length > 0; }

    /** Returns a caller-owned key copy; caller must erase it after provider construction. */
    public synchronized char[] copyApiKey() {
        if (closed) throw new IllegalStateException("ProviderRuntimeSettings is closed"); //$NON-NLS-1$
        return apiKey.clone();
    }

    @Override
    public synchronized void close() {
        Arrays.fill(apiKey, '\0');
        apiKey = new char[0];
        closed = true;
    }

    @Override
    public synchronized String toString() {
        return "ProviderRuntimeSettings[baseUri=" + baseUri + ", model=" + model //$NON-NLS-1$ //$NON-NLS-2$
                + ", apiKeyConfigured=" + (!closed && apiKey.length > 0) + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
