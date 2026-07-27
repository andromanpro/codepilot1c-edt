/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;

/**
 * Unit tests for {@link CodexResponsesStreamParser}. JSON is written with single quotes and
 * converted to double quotes to keep the literals readable.
 */
public class CodexResponsesStreamParserTest {

    private static String sse(String singleQuotedJson) {
        return "data: " + singleQuotedJson.replace('\'', '"'); //$NON-NLS-1$
    }

    @Test
    public void streamsTextThenCompletesWithUsage() {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        CodexResponsesStreamParser parser = new CodexResponsesStreamParser(chunks::add);
        boolean[] stopped = { false };
        Runnable complete = () -> stopped[0] = true;

        parser.process(sse("{'type':'response.output_text.delta','delta':'Hello '}"), complete); //$NON-NLS-1$
        parser.process(sse("{'type':'response.output_text.delta','delta':'world'}"), complete); //$NON-NLS-1$
        parser.process(sse("{'type':'response.completed','response':{'usage':"
            + "{'input_tokens':3,'output_tokens':2,'total_tokens':5}}}"), complete); //$NON-NLS-1$

        StringBuilder text = new StringBuilder();
        LlmStreamChunk usageChunk = null;
        boolean completed = false;
        for (LlmStreamChunk chunk : chunks) {
            if (chunk.getContent() != null) {
                text.append(chunk.getContent());
            }
            if (chunk.hasUsage()) {
                usageChunk = chunk;
            }
            if (chunk.isComplete()) {
                completed = true;
            }
        }

        assertEquals("Hello world", text.toString()); //$NON-NLS-1$
        assertTrue(completed);
        assertTrue(parser.isCompleted());
        assertTrue(stopped[0]);
        assertNotNull(usageChunk);
        assertEquals(5, usageChunk.getUsage().getTotalTokens());
    }

    @Test
    public void accumulatesFunctionCallAcrossArgumentDeltas() {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        CodexResponsesStreamParser parser = new CodexResponsesStreamParser(chunks::add);
        Runnable complete = () -> {
            // no-op
        };

        parser.process(sse("{'type':'response.output_item.added','item':"
            + "{'type':'function_call','id':'it_1','call_id':'call_1','name':'read'}}"), complete); //$NON-NLS-1$
        parser.process(sse("{'type':'response.function_call_arguments.delta',"
            + "'item_id':'it_1','delta':'{\\'path\\':'}"), complete); //$NON-NLS-1$
        parser.process(sse("{'type':'response.function_call_arguments.delta',"
            + "'item_id':'it_1','delta':'\\'a.txt\\'}'}"), complete); //$NON-NLS-1$
        parser.process(sse("{'type':'response.completed','response':{}}"), complete); //$NON-NLS-1$

        LlmStreamChunk toolChunk = null;
        String finishReason = null;
        for (LlmStreamChunk chunk : chunks) {
            if (chunk.hasToolCalls()) {
                toolChunk = chunk;
            }
            if (chunk.isComplete()) {
                finishReason = chunk.getFinishReason();
            }
        }

        assertNotNull(toolChunk);
        assertEquals(1, toolChunk.getToolCalls().size());
        assertEquals("read", toolChunk.getToolCalls().get(0).getName()); //$NON-NLS-1$
        assertEquals("call_1", toolChunk.getToolCalls().get(0).getId()); //$NON-NLS-1$
        assertEquals("{\"path\":\"a.txt\"}", toolChunk.getToolCalls().get(0).getArguments()); //$NON-NLS-1$
        assertEquals(LlmResponse.FINISH_REASON_TOOL_USE, finishReason);
    }
}
