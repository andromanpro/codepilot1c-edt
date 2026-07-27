/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.ProviderType;

/**
 * Tests for {@link ProviderCapabilities} focused on streaming-usage capability
 * gating added in Plan 2.3.
 */
public class ProviderCapabilitiesTest {

    @Test
    public void backendOptimizationCapabilityIsNotPartOfPublicApi() {
        String getter = "supportsBackend" + "Optimizations"; //$NON-NLS-1$ //$NON-NLS-2$
        String builder = "backend" + "Optimizations"; //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Arrays.stream(ProviderCapabilities.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals(getter)));
        assertFalse(Arrays.stream(ProviderCapabilities.Builder.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals(builder)));
        assertFalse(Arrays.stream(ProviderUtils.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals(getter)));
    }

    @Test
    public void codePilotBackendSupportsStreamUsageByDefault() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "auto")); //$NON-NLS-1$

        assertTrue(capabilities.isCodePilotBackend());
        assertTrue(capabilities.supportsStreamUsage());
    }

    @Test
    public void codePilotBackendExplicitModelSupportsStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "backend-coder-plus")); //$NON-NLS-1$

        assertTrue(capabilities.isCodePilotBackend());
        assertTrue(capabilities.supportsStreamUsage());
    }

    @Test
    public void codePilotBackendSupportsTextToolCallFallback() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "backend-coder-plus")); //$NON-NLS-1$

        assertTrue(capabilities.supportsTextToolCallFallback());
    }

    @Test
    public void genericOpenAiCompatibleDoesNotSupportTextToolCallFallback() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.OPENAI_COMPATIBLE, "gpt-4o")); //$NON-NLS-1$

        assertFalse(capabilities.supportsTextToolCallFallback());
    }

    @Test
    public void genericOpenAiCompatibleDoesNotSupportStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.OPENAI_COMPATIBLE, "gpt-4o")); //$NON-NLS-1$

        assertFalse(capabilities.isCodePilotBackend());
        assertFalse(capabilities.supportsStreamUsage());
    }

    @Test
    public void anthropicDoesNotSupportStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.ANTHROPIC, "claude-sonnet-4-5")); //$NON-NLS-1$

        assertFalse(capabilities.supportsStreamUsage());
    }

    @Test
    public void ollamaDoesNotSupportStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.OLLAMA, "llama3.2")); //$NON-NLS-1$

        assertFalse(capabilities.supportsStreamUsage());
    }

    @Test
    public void noneCapabilitiesDoNotSupportStreamUsage() {
        ProviderCapabilities none = ProviderCapabilities.none();

        assertFalse(none.supportsStreamUsage());
    }

    @Test
    public void builderStreamUsageFlagIsHonoured() {
        ProviderCapabilities explicit = ProviderCapabilities.builder()
                .streamUsage(true)
                .build();
        assertTrue(explicit.supportsStreamUsage());

        ProviderCapabilities unset = ProviderCapabilities.builder().build();
        assertFalse(unset.supportsStreamUsage());
    }

    @Test
    public void inferImageInputRecognizesModernMultimodalFamilies() {
        // The reported case: GPT-5.5 must be treated as multimodal.
        assertTrue(ProviderCapabilities.inferImageInputFromModel("gpt-5.5")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("gpt-5")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("gpt-5o-mini")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("GPT-5.5")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("gpt-4o")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("o1")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("o3-mini")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("claude-opus-4-8")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("anthropic/claude-3.5-sonnet")); //$NON-NLS-1$
        assertTrue(ProviderCapabilities.inferImageInputFromModel("gemini-2.0-flash")); //$NON-NLS-1$
    }

    @Test
    public void inferImageInputRejectsTextOnlyAndBlankModels() {
        assertFalse(ProviderCapabilities.inferImageInputFromModel(null));
        assertFalse(ProviderCapabilities.inferImageInputFromModel("")); //$NON-NLS-1$
        assertFalse(ProviderCapabilities.inferImageInputFromModel("text-embedding-3-large")); //$NON-NLS-1$
        assertFalse(ProviderCapabilities.inferImageInputFromModel("deepseek-chat")); //$NON-NLS-1$
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
