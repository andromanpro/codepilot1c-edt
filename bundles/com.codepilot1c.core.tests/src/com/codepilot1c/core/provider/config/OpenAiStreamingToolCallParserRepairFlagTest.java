package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

/**
 * Streaming tool calls finalized from truncated argument payloads must carry
 * the repaired flag so mutating tools can reject them.
 */
public class OpenAiStreamingToolCallParserRepairFlagTest {

    @Test
    public void completeArgumentsAreNotMarkedRepaired() {
        OpenAiStreamingToolCallParser parser = new OpenAiStreamingToolCallParser();
        parser.append(chunk(
                "[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"edit_file\"," //$NON-NLS-1$
                + "\"arguments\":\"{\\\"path\\\":\\\"a.bsl\\\",\\\"old_text\\\":\\\"x\\\",\\\"new_text\\\":\\\"y\\\"}\"}}]")); //$NON-NLS-1$

        List<ToolCall> calls = parser.drainCompletedToolCalls().toolCalls();
        assertEquals(1, calls.size());
        assertFalse(calls.get(0).isArgumentsRepaired());
    }

    @Test
    public void argumentsSplitAcrossChunksAreNotMarkedRepaired() {
        OpenAiStreamingToolCallParser parser = new OpenAiStreamingToolCallParser();
        parser.append(chunk(
                "[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"edit_file\"," //$NON-NLS-1$
                + "\"arguments\":\"{\\\"path\\\":\\\"a.bsl\\\",\"}}]")); //$NON-NLS-1$
        parser.append(chunk(
                "[{\"index\":0,\"function\":{\"arguments\":\"\\\"old_text\\\":\\\"x\\\",\\\"new_text\\\":\\\"y\\\"}\"}}]")); //$NON-NLS-1$

        List<ToolCall> calls = parser.drainCompletedToolCalls().toolCalls();
        assertEquals(1, calls.size());
        assertFalse(calls.get(0).isArgumentsRepaired());
    }

    @Test
    public void argumentsCutAfterContentQuoteAreMarkedRepaired() {
        // Stream cut right after "content":" — repair turns this into content:""
        OpenAiStreamingToolCallParser parser = new OpenAiStreamingToolCallParser();
        parser.append(chunk(
                "[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"edit_file\"," //$NON-NLS-1$
                + "\"arguments\":\"{\\\"path\\\":\\\"Module.bsl\\\",\\\"content\\\":\\\"\"}}]")); //$NON-NLS-1$

        OpenAiStreamingToolCallParser.DrainResult drainResult = parser.drainCompletedToolCalls();
        List<ToolCall> calls = drainResult.toolCalls();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).isArgumentsRepaired());
        assertTrue(calls.get(0).getArguments().contains("\"content\":\"\"")); //$NON-NLS-1$
        assertEquals(1, drainResult.repairedCount());
    }

    @Test
    public void argumentsCutMidContentAreMarkedRepaired() {
        OpenAiStreamingToolCallParser parser = new OpenAiStreamingToolCallParser();
        parser.append(chunk(
                "[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"edit_file\"," //$NON-NLS-1$
                + "\"arguments\":\"{\\\"path\\\":\\\"Module.bsl\\\",\\\"content\\\":\\\"Процедура Тест()\"}}]")); //$NON-NLS-1$

        List<ToolCall> calls = parser.drainCompletedToolCalls().toolCalls();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).isArgumentsRepaired());
    }

    private static JsonArray chunk(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }
}
