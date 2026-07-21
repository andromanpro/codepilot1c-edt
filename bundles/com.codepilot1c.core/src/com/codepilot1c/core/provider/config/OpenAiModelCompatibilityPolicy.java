package com.codepilot1c.core.provider.config;

import java.util.Locale;

import com.codepilot1c.core.model.LlmRequest;
import com.google.gson.JsonObject;

/**
 * Resolves model-specific execution quirks for OpenAI-compatible endpoints.
 */
final class OpenAiModelCompatibilityPolicy {

    private static final int LARGE_TOOL_RESULT_CHARS = 50_000;
    private static final int LARGE_REQUEST_ESTIMATE_CHARS = 120_000;
    private final OpenAiCompatibilityProfileResolver profileResolver = new OpenAiCompatibilityProfileResolver();

    ProviderExecutionPlan plan(LlmProviderConfig config, LlmRequest request, boolean requestedStreaming) {
        boolean streaming = requestedStreaming && config.isStreamingEnabled();
        JsonObject overrides = new JsonObject();
        String model = normalize(resolveModelName(config, request));
        OpenAiCompatibilityProfile profile = profileResolver.resolve(config, model);

        if (profile.isParallelToolCallsDisabled()) {
            overrides.addProperty("parallel_tool_calls", false); //$NON-NLS-1$
        }
        if (profile.hasDefaultTemperature()) {
            overrides.addProperty("temperature", profile.getDefaultTemperature()); //$NON-NLS-1$
        }
        if (request.hasTools()) {
            applyReasoningControl(overrides, profile.getReasoningControlStyle());
        }

        boolean largeContext = hasLargeToolResult(request) || estimateRequestChars(request) > LARGE_REQUEST_ESTIMATE_CHARS;
        boolean plannedStreaming = resolveStreaming(streaming, request.hasTools(), largeContext,
                profile.getToolStreamingPolicy());
        return ProviderExecutionPlan.of(plannedStreaming, overrides, reason(profile, request.hasTools(), largeContext),
                profile.getMaxTokensParameterName());
    }

    private static void applyReasoningControl(JsonObject overrides,
            OpenAiCompatibilityProfile.ReasoningControlStyle reasoningControlStyle) {
        if (reasoningControlStyle == OpenAiCompatibilityProfile.ReasoningControlStyle.BOOLEAN_ENABLE_THINKING_FALSE) {
            overrides.addProperty("enable_thinking", false); //$NON-NLS-1$
            return;
        }
        if (reasoningControlStyle == OpenAiCompatibilityProfile.ReasoningControlStyle.OBJECT_THINKING_TYPE_DISABLED) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled"); //$NON-NLS-1$ //$NON-NLS-2$
            overrides.add("thinking", thinking); //$NON-NLS-1$
        }
    }

    private static boolean resolveStreaming(boolean requestedStreaming, boolean hasTools, boolean largeContext,
            OpenAiCompatibilityProfile.ToolStreamingPolicy toolStreamingPolicy) {
        if (!hasTools) {
            return requestedStreaming;
        }
        switch (toolStreamingPolicy) {
        case NON_STREAM_FOR_TOOLS:
        case NON_STREAM_FOR_REASONING_REPLAY:
        case NON_STREAM_FOR_BACKEND_ROUTER:
            return false;
        case NON_STREAM_FOR_LARGE_CONTEXT:
            return !largeContext && requestedStreaming;
        case ALLOW:
        default:
            return requestedStreaming;
        }
    }

    private static String reason(OpenAiCompatibilityProfile profile, boolean hasTools, boolean largeContext) {
        if ("openai-compatible-default".equals(profile.getId())) { //$NON-NLS-1$
            return null;
        }
        if (!hasTools) {
            return profile.getId() + ": streaming allowed without tools"; //$NON-NLS-1$
        }
        switch (profile.getToolStreamingPolicy()) {
        case NON_STREAM_FOR_TOOLS:
            if ("minimax-m2-stable-tool-id".equals(profile.getId())) { //$NON-NLS-1$
                return "MiniMax M2: tool calls use non-stream mode for stable tool_call_id"; //$NON-NLS-1$
            }
            return profile.getId() + ": tool calls use non-stream mode"; //$NON-NLS-1$
        case NON_STREAM_FOR_REASONING_REPLAY:
            if ("codepilot-reasoning-replay".equals(profile.getId())) { //$NON-NLS-1$
                return "DeepSeek backend: tool calls use non-stream mode to preserve reasoning_content"; //$NON-NLS-1$
            }
            return profile.getId() + ": tool calls use non-stream mode to preserve reasoning_content"; //$NON-NLS-1$
        case NON_STREAM_FOR_BACKEND_ROUTER:
            return profile.getId() + ": auto routing with tools -> non-stream"; //$NON-NLS-1$
        case NON_STREAM_FOR_LARGE_CONTEXT:
            if (largeContext) {
                return profile.getId() + ": large tool context -> non-stream for stability"; //$NON-NLS-1$
            }
            return profile.getId() + ": streaming with compatibility request overrides"; //$NON-NLS-1$
        case ALLOW:
        default:
            return profile.getId();
        }
    }

    private String resolveModelName(LlmProviderConfig config, LlmRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return config.getModel() != null ? config.getModel() : ""; //$NON-NLS-1$
    }

    private static String normalize(String model) {
        return model != null ? model.toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
    }

    private boolean hasLargeToolResult(LlmRequest request) {
        return request.getMessages().stream()
                .filter(message -> message != null && message.isToolResult())
                .anyMatch(message -> message.getContent() != null && message.getContent().length() > LARGE_TOOL_RESULT_CHARS);
    }

    private int estimateRequestChars(LlmRequest request) {
        return request.getMessages().stream()
                .filter(message -> message != null && message.getContent() != null)
                .mapToInt(message -> message.getContent().length())
                .sum();
    }
}
