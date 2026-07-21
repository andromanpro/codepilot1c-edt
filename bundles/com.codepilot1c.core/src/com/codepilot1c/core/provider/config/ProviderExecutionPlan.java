package com.codepilot1c.core.provider.config;

import com.google.gson.JsonObject;

/**
 * Provider execution plan resolved before an LLM request is sent.
 */
final class ProviderExecutionPlan {

    private final boolean streaming;
    private final JsonObject requestOverrides;
    private final String reason;
    private final String maxTokensParameterName;

    private ProviderExecutionPlan(boolean streaming, JsonObject requestOverrides, String reason) {
        this(streaming, requestOverrides, reason, "max_tokens"); //$NON-NLS-1$
    }

    private ProviderExecutionPlan(boolean streaming, JsonObject requestOverrides, String reason,
            String maxTokensParameterName) {
        this.streaming = streaming;
        this.requestOverrides = requestOverrides != null ? requestOverrides : new JsonObject();
        this.reason = reason;
        this.maxTokensParameterName = maxTokensParameterName != null && !maxTokensParameterName.isBlank()
                ? maxTokensParameterName
                : "max_tokens"; //$NON-NLS-1$
    }

    static ProviderExecutionPlan streaming(boolean streaming) {
        return new ProviderExecutionPlan(streaming, new JsonObject(), null);
    }

    static ProviderExecutionPlan of(boolean streaming, JsonObject requestOverrides, String reason) {
        return new ProviderExecutionPlan(streaming, requestOverrides, reason);
    }

    static ProviderExecutionPlan of(boolean streaming, JsonObject requestOverrides, String reason,
            String maxTokensParameterName) {
        return new ProviderExecutionPlan(streaming, requestOverrides, reason, maxTokensParameterName);
    }

    boolean isStreaming() {
        return streaming;
    }

    JsonObject getRequestOverrides() {
        return requestOverrides;
    }

    String getReason() {
        return reason;
    }

    String getMaxTokensParameterName() {
        return maxTokensParameterName;
    }
}
