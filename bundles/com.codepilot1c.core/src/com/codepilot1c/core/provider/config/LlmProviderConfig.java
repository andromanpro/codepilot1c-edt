/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.codepilot1c.core.provider.codex.CodexOAuthConstants;

/**
 * Configuration for a single LLM provider.
 *
 * <p>This class represents a user-configured LLM provider with all necessary
 * settings to connect and communicate with the API.</p>
 */
public class LlmProviderConfig {

    private String id;
    private String name;
    private ProviderType type;
    private String baseUrl;
    private String apiKey;
    private String model;
    private int maxTokens;
    private String reasoningEffort;
    private Map<String, String> customHeaders;
    private boolean streamingEnabled;

    /**
     * Creates a new empty provider configuration with a generated UUID.
     */
    public LlmProviderConfig() {
        this.id = UUID.randomUUID().toString();
        this.type = ProviderType.OPENAI_COMPATIBLE;
        this.maxTokens = 4096;
        this.reasoningEffort = CodexOAuthConstants.DEFAULT_REASONING_EFFORT;
        this.customHeaders = new HashMap<>();
        this.streamingEnabled = true;
    }

    /**
     * Creates a provider configuration with the specified values.
     */
    public LlmProviderConfig(String id, String name, ProviderType type, String baseUrl,
            String apiKey, String model, int maxTokens) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.type = type != null ? type : ProviderType.OPENAI_COMPATIBLE;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens > 0 ? maxTokens : 4096;
        this.reasoningEffort = CodexOAuthConstants.DEFAULT_REASONING_EFFORT;
        this.customHeaders = new HashMap<>();
        this.streamingEnabled = true;
    }

    /**
     * Returns the unique identifier of this provider configuration.
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the user-defined display name for this provider.
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the API type of this provider.
     */
    public ProviderType getType() {
        return type;
    }

    public void setType(ProviderType type) {
        this.type = type;
    }

    /**
     * Returns the base URL for API requests.
     * Example: "https://api.openai.com/v1"
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = normalizeUrl(baseUrl);
    }

    /**
     * Returns the API key for authentication.
     * May be empty for local providers like Ollama.
     */
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Returns the model identifier to use.
     * Example: "gpt-4o", "claude-sonnet-4-20250514", "llama3.2"
     */
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /**
     * Returns the maximum number of tokens for responses.
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * Returns the Responses API reasoning effort. Missing or invalid persisted values fall back
     * to {@code medium}, keeping configurations created by older plugin versions compatible.
     */
    public String getReasoningEffort() {
        return normalizeReasoningEffort(reasoningEffort);
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = normalizeReasoningEffort(reasoningEffort);
    }

    /**
     * Returns custom HTTP headers to include in requests.
     */
    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public void setCustomHeaders(Map<String, String> customHeaders) {
        this.customHeaders = customHeaders != null ? customHeaders : new HashMap<>();
    }

    /**
     * Returns whether streaming is enabled for this provider.
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }

    /**
     * Checks if this provider configuration has all required fields set.
     */
    public boolean isConfigured() {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return false;
        }
        if (model == null || model.trim().isEmpty()) {
            return false;
        }
        // API key is optional for local providers (Ollama) and OAuth providers (Codex).
        if (type != ProviderType.OLLAMA && type != ProviderType.OPENAI_CODEX
                && (apiKey == null || apiKey.trim().isEmpty())) {
            return false;
        }
        return true;
    }

    /**
     * Returns the full URL for the chat completions endpoint.
     */
    public String getChatEndpointUrl() {
        if (baseUrl == null) {
            return null;
        }
        String base = normalizeUrl(baseUrl);
        String endpoint = type.getChatEndpoint();
        return base + endpoint;
    }

    /**
     * Returns the full URL for the models listing endpoint.
     */
    public String getModelsEndpointUrl() {
        if (baseUrl == null || !type.supportsModelListing()) {
            return null;
        }
        String base = normalizeUrl(baseUrl);
        String endpoint = type.getModelsEndpoint();
        return base + endpoint;
    }

    /**
     * Normalizes the URL by removing trailing slashes.
     */
    private String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        url = url.trim();
        while (url.endsWith("/")) { //$NON-NLS-1$
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Creates a deep copy of this configuration.
     */
    public LlmProviderConfig copy() {
        LlmProviderConfig copy = new LlmProviderConfig();
        copy.id = this.id;
        copy.name = this.name;
        copy.type = this.type;
        copy.baseUrl = this.baseUrl;
        copy.apiKey = this.apiKey;
        copy.model = this.model;
        copy.maxTokens = this.maxTokens;
        copy.reasoningEffort = this.reasoningEffort;
        copy.customHeaders = new HashMap<>(this.customHeaders);
        copy.streamingEnabled = this.streamingEnabled;
        return copy;
    }

    private static String normalizeReasoningEffort(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            for (String supported : CodexOAuthConstants.REASONING_EFFORTS) {
                if (supported.equals(normalized)) {
                    return supported;
                }
            }
        }
        return CodexOAuthConstants.DEFAULT_REASONING_EFFORT;
    }

    /**
     * Creates a copy with a new UUID (for duplicating providers).
     */
    public LlmProviderConfig copyWithNewId() {
        LlmProviderConfig copy = copy();
        copy.id = UUID.randomUUID().toString();
        copy.name = this.name + " (Copy)"; //$NON-NLS-1$
        return copy;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        LlmProviderConfig other = (LlmProviderConfig) obj;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "LlmProviderConfig[" + name + " (" + type + "), model=" + model + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
