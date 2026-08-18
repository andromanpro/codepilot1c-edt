/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, platform-neutral provider configuration owned by a runtime host.
 *
 * <p>The API key is accepted as mutable character data, copied on input, and
 * never exposed through an accessor or diagnostic string. Hosts should erase
 * their source array after building the configuration.</p>
 */
public final class ProviderConfiguration implements AutoCloseable {

    private final String id;
    private final String displayName;
    private final ProviderProtocol protocol;
    private final URI baseUri;
    private final String defaultModel;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Map<String, String> headers;
    private final char[] apiKey;
    private boolean closed;

    private ProviderConfiguration(Builder builder) {
        this.id = requireText(builder.id, "id"); //$NON-NLS-1$
        this.displayName = requireText(builder.displayName, "displayName"); //$NON-NLS-1$
        this.protocol = Objects.requireNonNull(builder.protocol, "protocol"); //$NON-NLS-1$
        this.baseUri = normalizedBaseUri(builder.baseUri);
        this.defaultModel = requireText(builder.defaultModel, "defaultModel"); //$NON-NLS-1$
        this.connectTimeout = positiveDuration(builder.connectTimeout, "connectTimeout"); //$NON-NLS-1$
        this.requestTimeout = positiveDuration(builder.requestTimeout, "requestTimeout"); //$NON-NLS-1$
        this.headers = immutableHeaders(builder.headers);
        this.apiKey = builder.apiKey == null ? new char[0] : builder.apiKey.clone();
    }

    /**
     * Creates a builder with safe HTTP defaults.
     *
     * @return empty configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return host-stable provider identifier */
    public String id() {
        return id;
    }

    /** @return host-visible display name */
    public String displayName() {
        return displayName;
    }

    /** @return selected provider wire protocol */
    public ProviderProtocol protocol() {
        return protocol;
    }

    /** @return normalized base URI without a trailing slash */
    public URI baseUri() {
        return baseUri;
    }

    /** @return default model selected by the host */
    public String defaultModel() {
        return defaultModel;
    }

    /** @return connection timeout */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** @return request timeout */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns custom HTTP headers as an immutable map. Header values are not
     * written to logs by this module; callers should likewise treat them as
     * potentially sensitive.
     *
     * @return custom request headers
     */
    public Map<String, String> headers() {
        return headers;
    }

    /** @return whether an API key was supplied */
    public synchronized boolean hasApiKey() {
        return !closed && apiKey.length > 0;
    }

    /**
     * Returns the chat-completions endpoint for the current protocol.
     *
     * @return OpenAI-compatible chat endpoint
     */
    public URI chatCompletionsEndpoint() {
        return URI.create(baseUri.toString() + "/chat/completions"); //$NON-NLS-1$
    }

    synchronized char[] copyApiKey() {
        if (closed) throw new IllegalStateException("provider configuration is closed"); //$NON-NLS-1$
        return apiKey.clone();
    }

    /** Erases the configuration-owned API-key copy. Safe to call repeatedly. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Arrays.fill(apiKey, '\0');
    }

    boolean hasHeader(String requestedName) {
        return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(requestedName));
    }

    @Override
    public String toString() {
        return "ProviderConfiguration[id=" + id //$NON-NLS-1$
                + ", protocol=" + protocol //$NON-NLS-1$
                + ", baseUri=" + baseUri //$NON-NLS-1$
                + ", defaultModel=" + defaultModel //$NON-NLS-1$
                + ", apiKeyConfigured=" + hasApiKey() //$NON-NLS-1$
                + "]"; //$NON-NLS-1$
    }

    /** Builder for immutable provider configuration. */
    public static final class Builder {
        private String id;
        private String displayName;
        private ProviderProtocol protocol = ProviderProtocol.OPENAI_COMPATIBLE;
        private URI baseUri;
        private String defaultModel;
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private Map<String, String> headers = Map.of();
        private char[] apiKey;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder protocol(ProviderProtocol protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * Copies mutable secret material. The supplied array remains owned by
         * the caller and should be erased after {@link #build()}. Replacing a
         * key erases the builder's previous copy.
         *
         * @param apiKey provider API key; null means no bearer authentication
         * @return this builder
         */
        public Builder apiKey(char[] apiKey) {
            clearApiKey();
            this.apiKey = apiKey == null ? null : apiKey.clone();
            return this;
        }

        /**
         * Creates an immutable configuration and erases the builder's
         * temporary secret copy whether validation succeeds or fails.
         * Configure the key again before reusing this builder for another
         * secret-bearing config.
         *
         * @return immutable configuration
         */
        public ProviderConfiguration build() {
            try {
                return new ProviderConfiguration(this);
            } finally {
                clearApiKey();
            }
        }

        private void clearApiKey() {
            if (apiKey != null) {
                Arrays.fill(apiKey, '\0');
                apiKey = null;
            }
        }
    }

    private static URI normalizedBaseUri(URI source) {
        Objects.requireNonNull(source, "baseUri"); //$NON-NLS-1$
        if (!source.isAbsolute() || source.getHost() == null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP URI"); //$NON-NLS-1$
        }
        String scheme = source.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("baseUri must use http or https"); //$NON-NLS-1$
        }
        if (source.getQuery() != null || source.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must not include query or fragment"); //$NON-NLS-1$
        }
        if (source.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUri must not include user info"); //$NON-NLS-1$
        }
        String normalized = source.toString();
        while (normalized.endsWith("/")) { //$NON-NLS-1$
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private static Map<String, String> immutableHeaders(Map<String, String> source) {
        Objects.requireNonNull(source, "headers"); //$NON-NLS-1$
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = requireText(entry.getKey(), "header name"); //$NON-NLS-1$
            String value = Objects.requireNonNull(entry.getValue(), "header value"); //$NON-NLS-1$
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("HTTP header must not contain line breaks"); //$NON-NLS-1$
            }
            copy.put(name, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Duration positiveDuration(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive"); //$NON-NLS-1$
        }
        return value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank"); //$NON-NLS-1$
        }
        return value;
    }
}
