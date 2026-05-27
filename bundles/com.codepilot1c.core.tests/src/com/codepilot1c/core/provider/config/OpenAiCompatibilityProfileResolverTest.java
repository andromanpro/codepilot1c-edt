package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenAiCompatibilityProfileResolverTest {

    private final OpenAiCompatibilityProfileResolver resolver = new OpenAiCompatibilityProfileResolver();

    @Test
    public void codePilotExplicitBackendUsesStandardToolProfile() {
        OpenAiCompatibilityProfile profile = resolver.resolve(
                configured(ProviderType.CODEPILOT_BACKEND, "backend-coder-plus")); //$NON-NLS-1$

        assertEquals("codepilot-standard", profile.getId()); //$NON-NLS-1$
        assertTrue(profile.hasDefaultTemperature());
        assertEquals(0.3, profile.getDefaultTemperature(), 0.0001);
        assertTrue(profile.isParallelToolCallsDisabled());
        assertEquals(OpenAiCompatibilityProfile.ReasoningControlStyle.BOOLEAN_ENABLE_THINKING_FALSE,
                profile.getReasoningControlStyle());
        assertEquals(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_LARGE_CONTEXT,
                profile.getToolStreamingPolicy());
        assertTrue(profile.supportsTextToolCallFallback());
        assertTrue(profile.supportsStreamUsage());
    }

    @Test
    public void codePilotAutoUsesBackendRouterProfile() {
        OpenAiCompatibilityProfile profile = resolver.resolve(
                configured(ProviderType.CODEPILOT_BACKEND, "auto")); //$NON-NLS-1$

        assertEquals("codepilot-auto-router", profile.getId()); //$NON-NLS-1$
        assertTrue(profile.isParallelToolCallsDisabled());
        assertEquals(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_BACKEND_ROUTER,
                profile.getToolStreamingPolicy());
        assertEquals(OpenAiCompatibilityProfile.ReasoningControlStyle.NONE,
                profile.getReasoningControlStyle());
    }

    @Test
    public void kimiAndMoonshotUseReasoningPreservingProfile() {
        OpenAiCompatibilityProfile kimi = resolver.resolve(
                configured(ProviderType.CODEPILOT_BACKEND, "kimi-k2.5-instruct")); //$NON-NLS-1$
        OpenAiCompatibilityProfile moonshot = resolver.resolve(
                configured(ProviderType.CODEPILOT_BACKEND, "moonshot-v1-8k")); //$NON-NLS-1$

        assertEquals("codepilot-reasoning-preserved", kimi.getId()); //$NON-NLS-1$
        assertEquals("codepilot-reasoning-preserved", moonshot.getId()); //$NON-NLS-1$
        assertTrue(kimi.isReasoningContentPreserved());
        assertEquals(0.6, kimi.getDefaultTemperature(), 0.0001);
        assertEquals(OpenAiCompatibilityProfile.ReasoningControlStyle.NONE,
                kimi.getReasoningControlStyle());
    }

    @Test
    public void deepSeekUsesReasoningReplayNonStreamProfile() {
        OpenAiCompatibilityProfile profile = resolver.resolve(
                configured(ProviderType.CODEPILOT_BACKEND, "deepseek-v4-flash")); //$NON-NLS-1$

        assertEquals("codepilot-reasoning-replay", profile.getId()); //$NON-NLS-1$
        assertEquals(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_REASONING_REPLAY,
                profile.getToolStreamingPolicy());
    }

    @Test
    public void miniMaxM2UsesStableToolIdProfile() {
        OpenAiCompatibilityProfile profile = resolver.resolve(
                configured(ProviderType.CODEPILOT_BACKEND, "minimax-m2.7")); //$NON-NLS-1$

        assertEquals("minimax-m2-stable-tool-id", profile.getId()); //$NON-NLS-1$
        assertEquals(OpenAiCompatibilityProfile.ToolStreamingPolicy.NON_STREAM_FOR_TOOLS,
                profile.getToolStreamingPolicy());
    }

    @Test
    public void genericOpenAiCompatibleKeepsNeutralDefaults() {
        OpenAiCompatibilityProfile profile = resolver.resolve(
                configured(ProviderType.OPENAI_COMPATIBLE, "gpt-4o")); //$NON-NLS-1$

        assertEquals("openai-compatible-default", profile.getId()); //$NON-NLS-1$
        assertFalse(profile.hasDefaultTemperature());
        assertFalse(profile.isParallelToolCallsDisabled());
        assertEquals(OpenAiCompatibilityProfile.ReasoningControlStyle.NONE,
                profile.getReasoningControlStyle());
        assertEquals(OpenAiCompatibilityProfile.ToolStreamingPolicy.ALLOW,
                profile.getToolStreamingPolicy());
        assertFalse(profile.supportsTextToolCallFallback());
        assertEquals("max_tokens", profile.getMaxTokensParameterName()); //$NON-NLS-1$
    }

    @Test
    public void genericGpt5UsesMaxCompletionTokensParameter() {
        OpenAiCompatibilityProfile profile = resolver.resolve(
                configured(ProviderType.OPENAI_COMPATIBLE, "gpt-5.4")); //$NON-NLS-1$

        assertEquals("openai-max-completion-tokens", profile.getId()); //$NON-NLS-1$
        assertEquals("max_completion_tokens", profile.getMaxTokensParameterName()); //$NON-NLS-1$
    }

    private static LlmProviderConfig configured(ProviderType type, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setType(type);
        config.setModel(model);
        config.setStreamingEnabled(true);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setName("provider"); //$NON-NLS-1$
        return config;
    }
}
