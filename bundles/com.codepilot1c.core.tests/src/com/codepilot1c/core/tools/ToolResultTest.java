/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for model-facing tool result formatting.
 */
public class ToolResultTest {

    @Test
    public void contentForLlmReturnsFullContentWithinLimit() {
        ToolResult result = ToolResult.success("short result"); //$NON-NLS-1$

        assertEquals("short result", result.getContentForLlm(100)); //$NON-NLS-1$
    }

    @Test
    public void payloadAccessorsPreserveFullJsonPayloads() {
        String successPayload = "{\"status\":\"ok\",\"data\":\"" + repeat("S", 260) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String failurePayload = "{\"status\":\"error\",\"message\":\"" + repeat("F", 260) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ToolResult success = ToolResult.success(successPayload);
        ToolResult failure = ToolResult.failure(failurePayload);
        int maxChars = 160;

        assertTrue(success.isSuccess());
        assertTrue(success.getContentForLlm(maxChars).length() <= maxChars);
        assertEquals(successPayload, success.getContent());
        assertFalse(failure.isSuccess());
        assertTrue(failure.getContentForLlm(maxChars).length() <= maxChars);
        assertEquals(failurePayload, failure.getErrorMessage());
    }

    @Test
    public void contentForLlmBoundsLargeSuccessfulResult() {
        String content = repeat("A", 120) + "middle" + repeat("Z", 120); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ToolResult result = ToolResult.success(content, ToolResult.ToolResultType.CODE);

        String llmContent = result.getContentForLlm(240);

        assertTrue(llmContent.length() <= 240);
        assertTrue(llmContent.contains("[tool result truncated by CodePilot1C]")); //$NON-NLS-1$
        assertTrue(llmContent.contains("original_length_chars: " + content.length())); //$NON-NLS-1$
        assertTrue(llmContent.contains("result_type: CODE")); //$NON-NLS-1$
        assertTrue(llmContent.contains("AAA")); //$NON-NLS-1$
        assertTrue(llmContent.contains("ZZZ")); //$NON-NLS-1$
    }

    @Test
    public void contentForLlmBoundsLargeFailureResult() {
        ToolResult result = ToolResult.failure("boom " + repeat("X", 200)); //$NON-NLS-1$ //$NON-NLS-2$

        String llmContent = result.getContentForLlm(160);

        assertTrue(llmContent.length() <= 160);
        assertTrue(llmContent.contains("[tool result truncated by CodePilot1C]")); //$NON-NLS-1$
        assertTrue(llmContent.contains("result_type: ERROR")); //$NON-NLS-1$
        assertTrue(llmContent.contains("Error: boom")); //$NON-NLS-1$
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
