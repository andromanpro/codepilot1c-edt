/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable non-secret runtime settings plus wipeable provider credential data.
 * This object owns its stored key copy; callers own and must erase every array
 * returned by {@link #copyProviderApiKey()}.
 */
public final class RuntimeConfiguration implements AutoCloseable {
    private final ResolvedSetting<URI> providerBaseUri;
    private final ResolvedSetting<String> providerModel;
    private final ResolvedSetting<Duration> providerConnectTimeout;
    private final ResolvedSetting<Duration> providerRequestTimeout;
    private final ResolvedSetting<java.util.Optional<URI>> mcpEndpoint;
    private final ResolvedSetting<Integer> agentMaxSteps;
    private final ResolvedSetting<Duration> agentTimeout;
    private final EnumMap<RuntimeSetting, ConfigurationSource> sources;
    private char[] providerApiKey;
    private boolean closed;

    RuntimeConfiguration(ResolvedSetting<URI> providerBaseUri, ResolvedSetting<String> providerModel,
            ResolvedSetting<Duration> providerConnectTimeout, ResolvedSetting<Duration> providerRequestTimeout,
            ResolvedSetting<java.util.Optional<URI>> mcpEndpoint, ResolvedSetting<Integer> agentMaxSteps,
            ResolvedSetting<Duration> agentTimeout, Map<RuntimeSetting, ConfigurationSource> sources,
            char[] providerApiKey) {
        this.providerBaseUri = Objects.requireNonNull(providerBaseUri, "providerBaseUri"); //$NON-NLS-1$
        this.providerModel = Objects.requireNonNull(providerModel, "providerModel"); //$NON-NLS-1$
        this.providerConnectTimeout = Objects.requireNonNull(providerConnectTimeout, "providerConnectTimeout"); //$NON-NLS-1$
        this.providerRequestTimeout = Objects.requireNonNull(providerRequestTimeout, "providerRequestTimeout"); //$NON-NLS-1$
        this.mcpEndpoint = Objects.requireNonNull(mcpEndpoint, "mcpEndpoint"); //$NON-NLS-1$
        this.agentMaxSteps = Objects.requireNonNull(agentMaxSteps, "agentMaxSteps"); //$NON-NLS-1$
        this.agentTimeout = Objects.requireNonNull(agentTimeout, "agentTimeout"); //$NON-NLS-1$
        this.sources = new EnumMap<>(RuntimeSetting.class);
        this.sources.putAll(sources);
        this.providerApiKey = providerApiKey == null ? new char[0] : providerApiKey.clone();
    }

    /** @return OpenAI-compatible provider base URI, separate from the MCP endpoint */
    public ResolvedSetting<URI> providerBaseUri() { return providerBaseUri; }
    public ResolvedSetting<String> providerModel() { return providerModel; }
    public ResolvedSetting<Duration> providerConnectTimeout() { return providerConnectTimeout; }
    public ResolvedSetting<Duration> providerRequestTimeout() { return providerRequestTimeout; }
    public ResolvedSetting<java.util.Optional<URI>> mcpEndpoint() { return mcpEndpoint; }
    public ResolvedSetting<Integer> agentMaxSteps() { return agentMaxSteps; }
    public ResolvedSetting<Duration> agentTimeout() { return agentTimeout; }

    /** Returns the source selected for any accepted setting. */
    public ConfigurationSource sourceOf(RuntimeSetting setting) {
        return sources.get(Objects.requireNonNull(setting, "setting")); //$NON-NLS-1$
    }

    /** @return true if a provider credential was loaded */
    public synchronized boolean hasProviderApiKey() {
        return !closed && providerApiKey.length > 0;
    }

    /**
     * Returns a new caller-owned credential copy. It never returns a String.
     * @return copied credential, or an empty array when no key is configured
     * @throws IllegalStateException after close
     */
    public synchronized char[] copyProviderApiKey() {
        ensureOpen();
        return providerApiKey.clone();
    }

    /**
     * Creates an independent provider integration snapshot. Closing this
     * configuration never erases the snapshot's separately-owned key copy.
     */
    public synchronized ProviderRuntimeSettings providerSettings() {
        ensureOpen();
        return new ProviderRuntimeSettings(providerBaseUri.value(), providerModel.value(), providerConnectTimeout.value(),
                providerRequestTimeout.value(), providerApiKey);
    }

    /** @return independent immutable MCP integration snapshot */
    public McpRuntimeSettings mcpSettings() {
        return new McpRuntimeSettings(mcpEndpoint.value(), mcpEndpoint.source());
    }

    /** @return independent immutable bounded-agent integration snapshot */
    public AgentRuntimeSettings agentSettings() {
        return new AgentRuntimeSettings(agentMaxSteps.value(), agentTimeout.value(), agentMaxSteps.source(), agentTimeout.source());
    }

    /** Erases the module-owned key. This operation is idempotent. */
    @Override
    public synchronized void close() {
        Arrays.fill(providerApiKey, '\0');
        providerApiKey = new char[0];
        closed = true;
    }

    @Override
    public synchronized String toString() {
        return "RuntimeConfiguration[providerBaseUri=" + providerBaseUri.value() //$NON-NLS-1$
                + ", providerModel=" + providerModel.value() //$NON-NLS-1$
                + ", mcpEndpoint=" + mcpEndpoint.value() //$NON-NLS-1$
                + ", providerApiKeyConfigured=" + (!closed && providerApiKey.length > 0) //$NON-NLS-1$
                + ", sources=" + sources + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("RuntimeConfiguration is closed"); //$NON-NLS-1$
    }
}
