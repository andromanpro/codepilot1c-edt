package com.codepilot1c.core.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.ProviderType;

public class ProviderUtilsTest {

    @Test
    public void codePilotBackendConfigPublishesBackendCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.CODEPILOT_BACKEND));

        assertTrue(capabilities.isCodePilotBackend());
        assertTrue(capabilities.supportsPromptCacheHeaders());
        assertTrue(capabilities.supportsResolvedModel());
        assertTrue(capabilities.supportsTextToolCallFallback());
    }

    @Test
    public void genericOpenAiConfigDoesNotPublishBackendCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.OPENAI_COMPATIBLE));

        assertFalse(capabilities.isCodePilotBackend());
        assertFalse(capabilities.supportsPromptCacheHeaders());
        assertFalse(capabilities.supportsResolvedModel());
        assertFalse(capabilities.supportsTextToolCallFallback());
        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsDocumentInput());
    }

    @Test
    public void dynamicProviderExposesCapabilitiesFromConfig() {
        DynamicLlmProvider provider = new DynamicLlmProvider(configured(ProviderType.CODEPILOT_BACKEND));

        assertTrue(ProviderUtils.isCodePilotBackend(provider));
    }

    @Test
    public void anthropicConfigPublishesImageAttachmentCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.ANTHROPIC));

        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsAttachmentMetadata());
    }

    @Test
    public void codePilotBackendVisionModelPublishesVisionCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "backend-vl-72b")); //$NON-NLS-1$

        assertTrue(capabilities.isCodePilotBackend());
        assertTrue(capabilities.supportsImageInput());
    }

    @Test
    public void codePilotBackendPublishesAttachmentCapabilitiesIndependentOfModelName() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "backend-coder-plus")); //$NON-NLS-1$

        assertTrue(capabilities.isCodePilotBackend());
        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsAttachmentMetadata());
    }

    @Test
    public void openAiCompatibleVisionModelPublishesImageCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.OPENAI_COMPATIBLE, "gpt-4o")); //$NON-NLS-1$

        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsAttachmentMetadata());
    }

    @Test
    public void ollamaConfigPublishesMultimodalCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.OLLAMA, "llama3.2-vision")); //$NON-NLS-1$

        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsDocumentInput());
        assertTrue(capabilities.supportsAttachmentMetadata());
    }

    private static LlmProviderConfig configured(ProviderType type) {
        return configured(type, "auto"); //$NON-NLS-1$
    }

    private static LlmProviderConfig configured(ProviderType type, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId("test-" + type.name()); //$NON-NLS-1$
        config.setName("test-" + type.name()); //$NON-NLS-1$
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel(model);
        return config;
    }
}
