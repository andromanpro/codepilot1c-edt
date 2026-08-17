package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.model.ToolCall;

public class ToolLoggerSensitiveResultTest {

    @Test
    public void sensitiveSuccessEntryContainsMetadataButNotContent() {
        String entry = ToolLogger.formatResultEntry("sensitive_tool", //$NON-NLS-1$
                ToolResult.success("stored-secret"), 17L, true); //$NON-NLS-1$

        assertFalse(entry.contains("stored-secret")); //$NON-NLS-1$
        assertTrue(entry.contains("RESULT: SUCCESS")); //$NON-NLS-1$
        assertTrue(entry.contains("Result type: TEXT")); //$NON-NLS-1$
        assertTrue(entry.contains("Content length: 13 chars")); //$NON-NLS-1$
        assertTrue(entry.contains("omitted: sensitive tool")); //$NON-NLS-1$
    }

    @Test
    public void sensitiveFailureEntryContainsNoErrorText() {
        String entry = ToolLogger.formatResultEntry("sensitive_tool", //$NON-NLS-1$
                ToolResult.failure("sensitive-error"), 9L, true); //$NON-NLS-1$

        assertFalse(entry.contains("sensitive-error")); //$NON-NLS-1$
        assertTrue(entry.contains("RESULT: FAILURE")); //$NON-NLS-1$
        assertTrue(entry.contains("Content length: 15 chars")); //$NON-NLS-1$
    }

    @Test
    public void nonSensitiveEntryPreservesContent() {
        String entry = ToolLogger.formatResultEntry("regular_tool", //$NON-NLS-1$
                ToolResult.success("ordinary-content"), 4L, false); //$NON-NLS-1$

        assertTrue(entry.contains("ordinary-content")); //$NON-NLS-1$
    }

    @Test
    public void sensitiveTracePayloadOmitsSuccessAndFailureText() {
        ToolCall call = new ToolCall("call-1", "sensitive_tool", "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> success = ToolExecutionService.buildToolResultTracePayload(
                call, ToolResult.success("stored-secret"), 12L, null, true); //$NON-NLS-1$
        Map<String, Object> failure = ToolExecutionService.buildToolResultTracePayload(
                call, ToolResult.failure("sensitive-error"), 13L, null, true); //$NON-NLS-1$

        assertEquals(Boolean.TRUE, success.get("content_omitted")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(13), success.get("content_length")); //$NON-NLS-1$
        assertFalse(success.containsKey("content")); //$NON-NLS-1$
        assertFalse(success.containsKey("error_message")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, failure.get("content_omitted")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(15), failure.get("content_length")); //$NON-NLS-1$
        assertFalse(failure.containsKey("content")); //$NON-NLS-1$
        assertFalse(failure.containsKey("error_message")); //$NON-NLS-1$
    }

    @Test
    public void sensitiveTracePayloadOmitsExceptionDetails() {
        ToolCall call = new ToolCall("call-1", "sensitive_tool", "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> payload = ToolExecutionService.buildToolResultTracePayload(
                call, null, 3L, new IllegalStateException("sensitive-error"), true); //$NON-NLS-1$

        assertEquals(Boolean.TRUE, payload.get("content_omitted")); //$NON-NLS-1$
        assertFalse(payload.containsKey("exception_type")); //$NON-NLS-1$
        assertFalse(payload.containsKey("exception_message")); //$NON-NLS-1$
        assertNull(payload.get("content")); //$NON-NLS-1$
    }

    @Test
    public void nonSensitiveTracePayloadPreservesExceptionDetails() {
        ToolCall call = new ToolCall("call-1", "regular_tool", "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> payload = ToolExecutionService.buildToolResultTracePayload(
                call, null, 3L, new IllegalStateException("ordinary-error"), false); //$NON-NLS-1$

        assertEquals("IllegalStateException", payload.get("exception_type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ordinary-error", payload.get("exception_message")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
