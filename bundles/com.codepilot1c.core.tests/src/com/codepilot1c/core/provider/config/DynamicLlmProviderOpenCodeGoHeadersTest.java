/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.net.http.HttpRequest;

import org.junit.Test;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;

/** Regression coverage for OpenCode Go session-affinity headers. */
public class DynamicLlmProviderOpenCodeGoHeadersTest {

    @Test
    public void opencodeGoRequestCarriesStableConversationSessionHeader() throws Exception {
        DynamicLlmProvider provider = provider("https://opencode.ai/zen/go/v1"); //$NON-NLS-1$
        LlmRequest first = request("session-123"); //$NON-NLS-1$
        LlmRequest second = request("session-123"); //$NON-NLS-1$

        HttpRequest firstHttp = buildHttpRequest(provider, first);
        HttpRequest secondHttp = buildHttpRequest(provider, second);

        assertEquals("session-123", header(firstHttp, "x-opencode-session")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("session-123", header(secondHttp, "x-opencode-session")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(header(firstHttp, "User-Agent").startsWith("CodePilot1C-EDT/")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void newConversationCanCarryDifferentOpencodeSessionHeader() throws Exception {
        DynamicLlmProvider provider = provider("https://opencode.ai/zen/go/v1"); //$NON-NLS-1$

        HttpRequest firstHttp = buildHttpRequest(provider, request("session-a")); //$NON-NLS-1$
        HttpRequest secondHttp = buildHttpRequest(provider, request("session-b")); //$NON-NLS-1$

        assertEquals("session-a", header(firstHttp, "x-opencode-session")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("session-b", header(secondHttp, "x-opencode-session")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nonOpencodeProviderDoesNotReceiveSessionAffinityHeader() throws Exception {
        DynamicLlmProvider provider = provider("https://api.openai.com/v1"); //$NON-NLS-1$

        HttpRequest http = buildHttpRequest(provider, request("session-123")); //$NON-NLS-1$

        assertFalse(http.headers().firstValue("x-opencode-session").isPresent()); //$NON-NLS-1$
    }

    private static DynamicLlmProvider provider(String baseUrl) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setName("test-provider"); //$NON-NLS-1$
        config.setType(ProviderType.OPENAI_COMPATIBLE);
        config.setBaseUrl(baseUrl);
        config.setApiKey("test-key"); //$NON-NLS-1$
        config.setModel("kimi-k3"); //$NON-NLS-1$
        return new DynamicLlmProvider(config, ignored -> "test-key", () -> 10); //$NON-NLS-1$
    }

    private static LlmRequest request(String providerSessionId) {
        return LlmRequest.builder()
                .addMessage(LlmMessage.user("ping")) //$NON-NLS-1$
                .providerSessionId(providerSessionId)
                .build();
    }

    private static HttpRequest buildHttpRequest(DynamicLlmProvider provider, LlmRequest request) throws Exception {
        Method bodyBuilder = DynamicLlmProvider.class.getDeclaredMethod(
                "buildRequestBody", LlmRequest.class, ProviderExecutionPlan.class); //$NON-NLS-1$
        bodyBuilder.setAccessible(true);
        ProviderExecutionPlan plan = ProviderExecutionPlan.streaming(false);
        String body = (String) bodyBuilder.invoke(provider, request, plan);

        Method requestBuilder = DynamicLlmProvider.class.getDeclaredMethod(
                "buildHttpRequest", String.class, LlmRequest.class); //$NON-NLS-1$
        requestBuilder.setAccessible(true);
        return (HttpRequest) requestBuilder.invoke(provider, body, request);
    }

    private static String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElseThrow(() ->
                new AssertionError("Missing header: " + name)); //$NON-NLS-1$
    }
}
