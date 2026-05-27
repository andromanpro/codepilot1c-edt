package com.codepilot1c.core.provider.config;

import java.util.Locale;

/**
 * Resolves neutral behavior profiles for OpenAI-compatible request handling.
 */
final class OpenAiCompatibilityProfileResolver {

    private static final double STANDARD_BACKEND_TEMPERATURE = 0.3d;
    private static final double REASONING_PRESERVED_TEMPERATURE = 0.6d;

    OpenAiCompatibilityProfile resolve(LlmProviderConfig config) {
        String model = config != null ? config.getModel() : null;
        return resolve(config, model);
    }

    OpenAiCompatibilityProfile resolve(LlmProviderConfig config, String model) {
        ProviderType type = config != null ? config.getType() : null;
        String normalizedModel = normalize(model);

        if (type == ProviderType.CODEPILOT_BACKEND) {
            return resolveCodePilotBackend(normalizedModel);
        }
        return resolveGenericOpenAiCompatible(normalizedModel);
    }

    private OpenAiCompatibilityProfile resolveCodePilotBackend(String model) {
        if ("auto".equals(model)) { //$NON-NLS-1$
            return codePilotBase("codepilot-auto-router") //$NON-NLS-1$
                    .disableParallelToolCalls()
                    .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_BACKEND_ROUTER)
                    .build();
        }
        if (isReasoningPreservedModel(model)) {
            return codePilotBase("codepilot-reasoning-preserved") //$NON-NLS-1$
                    .defaultTemperature(REASONING_PRESERVED_TEMPERATURE)
                    .disableParallelToolCalls()
                    .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_LARGE_CONTEXT)
                    .preserveReasoningContent(true)
                    .build();
        }
        if (model.startsWith("deepseek")) { //$NON-NLS-1$
            return codePilotBase("codepilot-reasoning-replay") //$NON-NLS-1$
                    .disableParallelToolCalls()
                    .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_REASONING_REPLAY)
                    .preserveReasoningContent(true)
                    .build();
        }
        if (isMiniMaxM2(model)) {
            return codePilotBase("minimax-m2-stable-tool-id") //$NON-NLS-1$
                    .disableParallelToolCalls()
                    .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_TOOLS)
                    .build();
        }
        return codePilotBase("codepilot-standard") //$NON-NLS-1$
                .defaultTemperature(STANDARD_BACKEND_TEMPERATURE)
                .disableParallelToolCalls()
                .reasoningControl(OpenAiCompatibilityProfile.ReasoningControlStyle.BOOLEAN_ENABLE_THINKING_FALSE)
                .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_LARGE_CONTEXT)
                .build();
    }

    private OpenAiCompatibilityProfile resolveGenericOpenAiCompatible(String model) {
        if (usesMaxCompletionTokensParameter(model)) {
            return OpenAiCompatibilityProfile.builder("openai-max-completion-tokens") //$NON-NLS-1$
                    .maxTokensParameterName("max_completion_tokens") //$NON-NLS-1$
                    .build();
        }
        if (isMiniMaxM2(model)) {
            return OpenAiCompatibilityProfile.builder("minimax-m2-stable-tool-id") //$NON-NLS-1$
                    .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_TOOLS)
                    .build();
        }
        if (model.contains("glm-5")) { //$NON-NLS-1$
            return OpenAiCompatibilityProfile.builder("reasoning-first-tool-streaming") //$NON-NLS-1$
                    .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_TOOLS)
                    .build();
        }
        if (model.contains("kimi-k2.5") || model.contains("kimi-k2")) { //$NON-NLS-1$ //$NON-NLS-2$
            return OpenAiCompatibilityProfile.builder("moonshot-thinking-disabled") //$NON-NLS-1$
                    .defaultTemperature(REASONING_PRESERVED_TEMPERATURE)
                    .reasoningControl(OpenAiCompatibilityProfile.ReasoningControlStyle.OBJECT_THINKING_TYPE_DISABLED)
                    .toolStreamingPolicy(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_LARGE_CONTEXT)
                    .build();
        }
        return OpenAiCompatibilityProfile.builder("openai-compatible-default").build(); //$NON-NLS-1$
    }

    private static OpenAiCompatibilityProfile.Builder codePilotBase(String id) {
        return OpenAiCompatibilityProfile.builder(id)
                .textToolCallFallback(true)
                .streamUsage(true);
    }

    private static boolean isReasoningPreservedModel(String model) {
        return model.startsWith("kimi") || model.startsWith("moonshot"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static boolean isMiniMaxM2(String model) {
        if (model == null) {
            return false;
        }
        int idx = model.indexOf("minimax-m2"); //$NON-NLS-1$
        if (idx < 0) {
            return false;
        }
        if (idx > 0 && Character.isLetterOrDigit(model.charAt(idx - 1))) {
            return false;
        }
        int end = idx + "minimax-m2".length(); //$NON-NLS-1$
        if (end == model.length()) {
            return true;
        }
        char next = model.charAt(end);
        if (next == '.') {
            return true;
        }
        return !Character.isLetterOrDigit(next);
    }

    static boolean usesMaxCompletionTokensParameter(String model) {
        String normalized = normalize(model);
        return normalized.startsWith("gpt-5") //$NON-NLS-1$
                || isOpenAiReasoningFamily(normalized, "o1") //$NON-NLS-1$
                || isOpenAiReasoningFamily(normalized, "o3") //$NON-NLS-1$
                || isOpenAiReasoningFamily(normalized, "o4"); //$NON-NLS-1$
    }

    private static boolean isOpenAiReasoningFamily(String model, String prefix) {
        if (model.equals(prefix)) {
            return true;
        }
        if (!model.startsWith(prefix)) {
            return false;
        }
        char next = model.charAt(prefix.length());
        return !Character.isLetterOrDigit(next);
    }

    private static String normalize(String model) {
        return model != null ? model.toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
    }
}
