package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;

public class OpenAiStreamingSessionTest {

    @Test
    public void glmFixtureProducesReasoningAndToolCall() throws Exception {
        assertFixtureProducesToolUse("glm5_reasoning_then_toolcall.sse", "call_glm", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"operation\":\"status\"}", true); //$NON-NLS-1$
    }

    @Test
    public void minimaxFixtureProducesReasoningAndToolCall() throws Exception {
        assertFixtureProducesToolUse("minimax_reasoning_then_toolcall.sse", "call_minimax", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"operation\":\"status\",\"repo_path\":\".\"}", true); //$NON-NLS-1$
    }

    @Test
    public void structuredToolCallFixtureProducesCleanToolCallWithoutFallbackSignals() throws Exception {
        OpenAiStreamingSession session = new OpenAiStreamingSession("fixture-structured", true, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = replayFixture("structured_toolcall_clean.sse", session); //$NON-NLS-1$

        assertFalse(session.getSummary().shouldFallbackToNonStreaming());
        assertEquals(0, session.getSummary().getParseFailures().get());
        assertEquals(0, session.getSummary().getOpaqueChunks().get());
        assertNotNull(findToolChunk(chunks));
    }

    @Test
    public void repairsIncompleteArgumentsAtFinish() {
        OpenAiStreamingSession session = new OpenAiStreamingSession("repair", true, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        String finishReason = session.processLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"{\\\"operation\\\":\\\"status\\\"\"}}]},\"finish_reason\":\"tool_calls\"}]}", //$NON-NLS-1$
                chunks::add);
        if (finishReason != null) {
            chunks.add(LlmStreamChunk.complete(finishReason));
        }

        LlmStreamChunk toolChunk = findToolChunk(chunks);
        assertNotNull(toolChunk);
        assertEquals("{\"operation\":\"status\"}", toolChunk.getToolCalls().get(0).getArguments()); //$NON-NLS-1$
        assertEquals(1, session.getSummary().getRepairedToolCalls().get());
        assertFalse(session.getSummary().shouldFallbackToNonStreaming());
    }

    @Test
    public void reusedIndexCollisionPreservesBothToolCalls() {
        OpenAiStreamingSession session = new OpenAiStreamingSession("collision", true, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"{\\\"operation\\\":\\\"status\\\"}\"}}]},\"finish_reason\":null}]}", //$NON-NLS-1$
                chunks::add);
        String finishReason = session.processLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"{\\\"operation\\\":\\\"diff\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}", //$NON-NLS-1$
                chunks::add);
        if (finishReason != null) {
            chunks.add(LlmStreamChunk.complete(finishReason));
        }

        LlmStreamChunk toolChunk = findToolChunk(chunks);
        assertNotNull(toolChunk);
        assertEquals(2, toolChunk.getToolCalls().size());
        assertEquals("call_1", toolChunk.getToolCalls().get(0).getId()); //$NON-NLS-1$
        assertEquals("call_2", toolChunk.getToolCalls().get(1).getId()); //$NON-NLS-1$
    }

    @Test
    public void completesPendingToolCallsOnDoneWithoutFinishReason() {
        OpenAiStreamingSession session = new OpenAiStreamingSession("done", true, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_done\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"{\\\"operation\\\":\\\"status\\\"\"}}]},\"finish_reason\":null}]}", //$NON-NLS-1$
                chunks::add);
        String finishReason = session.completePendingToolCalls(chunks::add);
        if (finishReason != null) {
            chunks.add(LlmStreamChunk.complete(finishReason));
        }

        LlmStreamChunk toolChunk = findToolChunk(chunks);
        assertNotNull(toolChunk);
        assertEquals("call_done", toolChunk.getToolCalls().get(0).getId()); //$NON-NLS-1$
        assertEquals(LlmResponse.FINISH_REASON_TOOL_USE, lastFinishReason(chunks));
    }

    @Test
    public void unrecoverableArgumentsTriggerFallbackSignal() {
        OpenAiStreamingSession session = new OpenAiStreamingSession("truncated", true, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_bad\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"{bad\"}}]},\"finish_reason\":\"tool_calls\"}]}", //$NON-NLS-1$
                chunks::add);

        assertEquals(1, session.getSummary().getTruncatedToolCalls().get());
        assertTrue(session.getSummary().shouldFallbackToNonStreaming());
        assertNotNull(chunks);
    }

    @Test
    public void nonObjectArgumentsDoNotEmitToolCall() {
        OpenAiStreamingSession session = new OpenAiStreamingSession("non-object", true, //$NON-NLS-1$
                new OpenAiStreamingToolCallParser());
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_array\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"[1,2]\"}}]},\"finish_reason\":\"tool_calls\"}]}", //$NON-NLS-1$
                chunks::add);

        assertEquals(1, session.getSummary().getTruncatedToolCalls().get());
        assertTrue(session.getSummary().shouldFallbackToNonStreaming());
        assertFalse(chunks.stream().anyMatch(LlmStreamChunk::hasToolCalls));
    }

    @Test
    public void errorPayloadProducesStructuredErrorChunk() {
        OpenAiStreamingSession session = new OpenAiStreamingSession("error", true, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine("data: {\"error\":{\"message\":\"rate limited\"}}", chunks::add); //$NON-NLS-1$

        assertEquals(1, session.getSummary().getErrorChunks().get());
        assertTrue(session.getSummary().hasTerminalError());
        assertEquals("rate limited", chunks.get(0).getErrorMessage()); //$NON-NLS-1$
    }

    private void assertFixtureProducesToolUse(String fixtureName, String expectedId,
            String expectedArguments, boolean expectReasoning) throws Exception {
        OpenAiStreamingSession session = new OpenAiStreamingSession("fixture-" + fixtureName, true, //$NON-NLS-1$
                new OpenAiStreamingToolCallParser());
        List<LlmStreamChunk> chunks = replayFixture(fixtureName, session);

        LlmStreamChunk toolChunk = findToolChunk(chunks);
        assertNotNull(toolChunk);
        assertEquals(expectedId, toolChunk.getToolCalls().get(0).getId());
        assertEquals("git_inspect", toolChunk.getToolCalls().get(0).getName()); //$NON-NLS-1$
        assertEquals(expectedArguments, toolChunk.getToolCalls().get(0).getArguments());
        assertEquals(LlmResponse.FINISH_REASON_TOOL_USE, lastFinishReason(chunks));

        boolean hasReasoning = chunks.stream().anyMatch(chunk -> chunk.getReasoningContent() != null);
        assertEquals(expectReasoning, hasReasoning);
        assertFalse(session.getSummary().shouldFallbackToNonStreaming());
    }

    private List<LlmStreamChunk> replayFixture(String fixtureName, OpenAiStreamingSession session) throws IOException {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        String finishReason = null;
        for (String line : readFixtureLines(fixtureName)) {
            String currentFinishReason = session.processLine(line, chunks::add);
            if (currentFinishReason != null) {
                finishReason = currentFinishReason;
            }
        }
        if (finishReason != null) {
            chunks.add(LlmStreamChunk.complete(finishReason));
        }
        return chunks;
    }

    private List<String> readFixtureLines(String fixtureName) throws IOException {
        try (InputStream in = OpenAiStreamingSessionTest.class
                .getResourceAsStream("fixtures/" + fixtureName)) { //$NON-NLS-1$
            assertNotNull("fixture not found on test classpath: fixtures/" + fixtureName, in); //$NON-NLS-1$
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.toList());
            }
        }
    }

    private LlmStreamChunk findToolChunk(List<LlmStreamChunk> chunks) {
        return chunks.stream().filter(LlmStreamChunk::hasToolCalls).findFirst().orElse(null);
    }

    private String lastFinishReason(List<LlmStreamChunk> chunks) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).isComplete()) {
                return chunks.get(i).getFinishReason();
            }
        }
        return null;
    }
}
