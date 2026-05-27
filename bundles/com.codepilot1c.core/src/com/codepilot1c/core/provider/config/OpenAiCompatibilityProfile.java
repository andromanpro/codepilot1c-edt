package com.codepilot1c.core.provider.config;

/**
 * Neutral request-behavior profile for OpenAI-compatible endpoints.
 */
final class OpenAiCompatibilityProfile {

    enum ReasoningControlStyle {
        NONE,
        BOOLEAN_ENABLE_THINKING_FALSE,
        OBJECT_THINKING_TYPE_DISABLED
    }

    enum ToolStreamingPolicy {
        ALLOW,
        NON_STREAM_FOR_TOOLS,
        NON_STREAM_FOR_LARGE_CONTEXT,
        NON_STREAM_FOR_REASONING_REPLAY,
        NON_STREAM_FOR_BACKEND_ROUTER
    }

    private final String id;
    private final boolean hasDefaultTemperature;
    private final double defaultTemperature;
    private final boolean parallelToolCallsDisabled;
    private final ReasoningControlStyle reasoningControlStyle;
    private final ToolStreamingPolicy toolStreamingPolicy;
    private final boolean textToolCallFallback;
    private final boolean reasoningContentPreserved;
    private final boolean streamUsage;
    private final String maxTokensParameterName;

    private OpenAiCompatibilityProfile(Builder builder) {
        this.id = builder.id;
        this.hasDefaultTemperature = builder.hasDefaultTemperature;
        this.defaultTemperature = builder.defaultTemperature;
        this.parallelToolCallsDisabled = builder.parallelToolCallsDisabled;
        this.reasoningControlStyle = builder.reasoningControlStyle;
        this.toolStreamingPolicy = builder.toolStreamingPolicy;
        this.textToolCallFallback = builder.textToolCallFallback;
        this.reasoningContentPreserved = builder.reasoningContentPreserved;
        this.streamUsage = builder.streamUsage;
        this.maxTokensParameterName = builder.maxTokensParameterName;
    }

    static Builder builder(String id) {
        return new Builder(id);
    }

    String getId() {
        return id;
    }

    boolean hasDefaultTemperature() {
        return hasDefaultTemperature;
    }

    double getDefaultTemperature() {
        return defaultTemperature;
    }

    boolean isParallelToolCallsDisabled() {
        return parallelToolCallsDisabled;
    }

    ReasoningControlStyle getReasoningControlStyle() {
        return reasoningControlStyle;
    }

    ToolStreamingPolicy getToolStreamingPolicy() {
        return toolStreamingPolicy;
    }

    boolean supportsTextToolCallFallback() {
        return textToolCallFallback;
    }

    boolean isReasoningContentPreserved() {
        return reasoningContentPreserved;
    }

    boolean supportsStreamUsage() {
        return streamUsage;
    }

    String getMaxTokensParameterName() {
        return maxTokensParameterName;
    }

    static final class Builder {

        private final String id;
        private boolean hasDefaultTemperature;
        private double defaultTemperature;
        private boolean parallelToolCallsDisabled;
        private ReasoningControlStyle reasoningControlStyle = ReasoningControlStyle.NONE;
        private ToolStreamingPolicy toolStreamingPolicy = ToolStreamingPolicy.ALLOW;
        private boolean textToolCallFallback;
        private boolean reasoningContentPreserved;
        private boolean streamUsage;
        private String maxTokensParameterName = "max_tokens"; //$NON-NLS-1$

        private Builder(String id) {
            this.id = id;
        }

        Builder defaultTemperature(double defaultTemperature) {
            this.hasDefaultTemperature = true;
            this.defaultTemperature = defaultTemperature;
            return this;
        }

        Builder disableParallelToolCalls() {
            this.parallelToolCallsDisabled = true;
            return this;
        }

        Builder reasoningControl(ReasoningControlStyle reasoningControlStyle) {
            this.reasoningControlStyle = reasoningControlStyle != null
                    ? reasoningControlStyle
                    : ReasoningControlStyle.NONE;
            return this;
        }

        Builder toolStreamingPolicy(ToolStreamingPolicy toolStreamingPolicy) {
            this.toolStreamingPolicy = toolStreamingPolicy != null
                    ? toolStreamingPolicy
                    : ToolStreamingPolicy.ALLOW;
            return this;
        }

        Builder textToolCallFallback(boolean textToolCallFallback) {
            this.textToolCallFallback = textToolCallFallback;
            return this;
        }

        Builder preserveReasoningContent(boolean reasoningContentPreserved) {
            this.reasoningContentPreserved = reasoningContentPreserved;
            return this;
        }

        Builder streamUsage(boolean streamUsage) {
            this.streamUsage = streamUsage;
            return this;
        }

        Builder maxTokensParameterName(String maxTokensParameterName) {
            this.maxTokensParameterName = maxTokensParameterName != null && !maxTokensParameterName.isBlank()
                    ? maxTokensParameterName
                    : "max_tokens"; //$NON-NLS-1$
            return this;
        }

        OpenAiCompatibilityProfile build() {
            return new OpenAiCompatibilityProfile(this);
        }
    }
}
