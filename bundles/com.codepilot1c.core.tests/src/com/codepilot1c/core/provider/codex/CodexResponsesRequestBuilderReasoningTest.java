/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.codepilot1c.core.model.LlmRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CodexResponsesRequestBuilderReasoningTest {

    @Test
    public void serializesConfiguredReasoningEffort() {
        LlmRequest request = LlmRequest.builder().userMessage("test").build(); //$NON-NLS-1$

        JsonObject body = JsonParser.parseString(new CodexResponsesRequestBuilder()
            .build(request, "gpt-5.6-sol", 4096, true, "xhigh")) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();

        assertEquals("xhigh", body.getAsJsonObject("reasoning").get("effort").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void invalidReasoningEffortFallsBackToMedium() {
        LlmRequest request = LlmRequest.builder().userMessage("test").build(); //$NON-NLS-1$

        JsonObject body = JsonParser.parseString(new CodexResponsesRequestBuilder()
            .build(request, "gpt-5.6-sol", 4096, true, "unsupported")) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();

        assertEquals(CodexOAuthConstants.DEFAULT_REASONING_EFFORT,
            body.getAsJsonObject("reasoning").get("effort").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
