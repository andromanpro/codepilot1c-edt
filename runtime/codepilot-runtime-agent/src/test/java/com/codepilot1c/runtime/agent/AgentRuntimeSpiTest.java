/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.Test;

import com.google.gson.JsonObject;

public class AgentRuntimeSpiTest {

    @Test
    public void streamingEventsAreOrderedExactlyOnceAndLateDeltasAreDetached() throws Exception {
        List<String> events = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        AtomicReference<StreamObserver> firstObserver = new AtomicReference<>();
        StreamingAgentModel model = (request, cancellation, observer) -> {
            if (turn.getAndIncrement() == 0) {
                firstObserver.set(observer);
                observer.onReasoningDelta("think"); //$NON-NLS-1$
                observer.onTextDelta("working"); //$NON-NLS-1$
                return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                        new ToolCall("call-1", "echo", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            observer.onTextDelta("done"); //$NON-NLS-1$
            return CompletableFuture.completedFuture(AgentMessage.Assistant.text("done")); //$NON-NLS-1$
        };
        AgentEventListener listener = recordingListener(events);

        try (AgentRuntime runtime = streamingRuntime(
                model, echoTools(), listener, ToolApprover.ALLOW_ALL)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);
            firstObserver.get().onTextDelta("too-late"); //$NON-NLS-1$

            assertEquals(AgentResult.Status.COMPLETED, result.status());
            assertEquals(List.of(
                    "step:1", //$NON-NLS-1$
                    "reasoning:1:think", //$NON-NLS-1$
                    "text:1:working", //$NON-NLS-1$
                    "assistant:1:tools", //$NON-NLS-1$
                    "tool-start:1:call-1", //$NON-NLS-1$
                    "tool-result:1:call-1:OK", //$NON-NLS-1$
                    "step:2", //$NON-NLS-1$
                    "text:2:done", //$NON-NLS-1$
                    "assistant:2:done", //$NON-NLS-1$
                    "finished:COMPLETED"), events); //$NON-NLS-1$
        }
    }

    @Test
    public void streamingCapableModelRemainsBufferedUntilExplicitlyEnabled() throws Exception {
        AtomicInteger bufferedCompletions = new AtomicInteger();
        AtomicInteger streamingCompletions = new AtomicInteger();
        StreamingAgentModel model = new StreamingAgentModel() {
            @Override
            public java.util.concurrent.CompletionStage<AgentMessage.Assistant> complete(
                    AgentModel.Request request, CancellationToken cancellation) {
                bufferedCompletions.incrementAndGet();
                return CompletableFuture.completedFuture(AgentMessage.Assistant.text("buffered")); //$NON-NLS-1$
            }

            @Override
            public java.util.concurrent.CompletionStage<AgentMessage.Assistant> complete(
                    AgentModel.Request request, CancellationToken cancellation, StreamObserver observer) {
                streamingCompletions.incrementAndGet();
                observer.onTextDelta("streamed"); //$NON-NLS-1$
                return CompletableFuture.completedFuture(AgentMessage.Assistant.text("streamed")); //$NON-NLS-1$
            }
        };
        List<String> bufferedEvents = new ArrayList<>();
        try (AgentRuntime runtime = runtime(model, emptyTools(),
                recordingListener(bufferedEvents), ToolApprover.ALLOW_ALL)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);
            assertEquals(Optional.of("buffered"), result.text()); //$NON-NLS-1$
        }

        List<String> streamingEvents = new ArrayList<>();
        try (AgentRuntime runtime = streamingRuntime(model, emptyTools(),
                recordingListener(streamingEvents), ToolApprover.ALLOW_ALL)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);
            assertEquals(Optional.of("streamed"), result.text()); //$NON-NLS-1$
        }

        assertEquals(1, bufferedCompletions.get());
        assertEquals(1, streamingCompletions.get());
        assertEquals(List.of(
                "step:1", "assistant:1:buffered", "finished:COMPLETED"), bufferedEvents); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of(
                "step:1", "text:1:streamed", //$NON-NLS-1$ //$NON-NLS-2$
                "assistant:1:streamed", "finished:COMPLETED"), streamingEvents); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void bufferedModelUsesOriginalCompletionPathWithoutDeltaEvents() throws Exception {
        AtomicInteger completions = new AtomicInteger();
        List<String> events = new ArrayList<>();
        AgentModel buffered = (request, cancellation) -> {
            completions.incrementAndGet();
            return CompletableFuture.completedFuture(AgentMessage.Assistant.text("buffered")); //$NON-NLS-1$
        };

        try (AgentRuntime runtime = runtime(
                buffered, emptyTools(), recordingListener(events), ToolApprover.ALLOW_ALL)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);

            assertEquals(Optional.of("buffered"), result.text()); //$NON-NLS-1$
            assertEquals(1, completions.get());
            assertEquals(List.of(
                    "step:1", "assistant:1:buffered", "finished:COMPLETED"), events); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void denialBecomesToolResultAndLoopContinuesInCallOrder() throws Exception {
        AtomicInteger turn = new AtomicInteger();
        List<String> approved = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        AgentModel model = (modelRequest, cancellation) -> {
            if (turn.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                        new ToolCall("denied", "echo", "{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new ToolCall("allowed", "echo", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            List<AgentMessage.Tool> results = modelRequest.messages().stream()
                    .filter(AgentMessage.Tool.class::isInstance)
                    .map(AgentMessage.Tool.class::cast)
                    .toList();
            assertEquals(List.of("CONFIRMATION_DENIED", "OK"), //$NON-NLS-1$ //$NON-NLS-2$
                    results.stream().map(result -> result.result().code()).toList());
            assertEquals("Not approved for this run", results.get(0).result().message()); //$NON-NLS-1$
            return CompletableFuture.completedFuture(AgentMessage.Assistant.text("continued")); //$NON-NLS-1$
        };
        ToolRuntime tools = tools((name, arguments, cancellation) -> {
            executed.add(name);
            return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
        });
        ToolApprover approver = (call, definition, cancellation) -> {
            approved.add(call.id());
            return CompletableFuture.completedFuture("denied".equals(call.id()) //$NON-NLS-1$
                    ? ToolApprover.Decision.deny("Not approved for this run") //$NON-NLS-1$
                    : ToolApprover.Decision.allow());
        };

        try (AgentRuntime runtime = runtime(model, tools, AgentEventListener.NOOP, approver)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);

            assertEquals(AgentResult.Status.COMPLETED, result.status());
            assertEquals(List.of("denied", "allowed"), approved); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(List.of("echo"), executed); //$NON-NLS-1$
        }
    }

    @Test
    public void exceptionalApprovalIsTypedStepFailureAndDoesNotExecuteTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRuntime tools = tools((name, arguments, cancellation) -> {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
        });
        ToolApprover approver = (call, definition, cancellation) ->
                CompletableFuture.failedFuture(new IllegalStateException("policy unavailable")); //$NON-NLS-1$

        try (AgentRuntime runtime = runtime(toolCallingModel(), tools,
                AgentEventListener.NOOP, approver)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);

            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(AgentError.Code.TOOL_APPROVAL, result.error().orElseThrow().code());
            assertEquals("Tool approval failed", result.error().orElseThrow().message()); //$NON-NLS-1$
            assertEquals(0, executions.get());
        }
    }

    @Test
    public void typedProviderCancellationFinishesCancelledWithSafeRuntimeMessage() throws Exception {
        String providerMessage = "provider-private-cancellation-detail"; //$NON-NLS-1$
        AgentModel model = (modelRequest, cancellation) -> CompletableFuture.failedFuture(
                new AgentModelException(AgentError.Code.CANCELLED, providerMessage, -1));

        try (AgentRuntime runtime = runtime(model, emptyTools(),
                AgentEventListener.NOOP, ToolApprover.ALLOW_ALL)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);

            assertEquals(AgentResult.Status.CANCELLED, result.status());
            assertEquals(AgentError.Code.CANCELLED, result.error().orElseThrow().code());
            assertEquals("Provider request was cancelled", result.error().orElseThrow().message()); //$NON-NLS-1$
            assertFalse(result.error().orElseThrow().message().contains(providerMessage));
        }
    }

    @Test
    public void synchronousApprovalExceptionIsTypedAndNeverEscapesRun() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRuntime tools = tools((name, arguments, cancellation) -> {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
        });
        ToolApprover approver = (call, definition, cancellation) -> {
            throw new IllegalStateException("policy unavailable"); //$NON-NLS-1$
        };

        try (AgentRuntime runtime = runtime(toolCallingModel(), tools,
                AgentEventListener.NOOP, approver)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);

            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(AgentError.Code.TOOL_APPROVAL, result.error().orElseThrow().code());
            assertEquals(0, executions.get());
        }
    }

    @Test
    public void hostileApprovalStageCannotStrandRunDuringObservation() throws Exception {
        CompletableFuture<ToolApprover.Decision> hostile =
                new CompletableFuture<ToolApprover.Decision>() {
                    @Override
                    public CompletableFuture<ToolApprover.Decision> whenComplete(
                            BiConsumer<? super ToolApprover.Decision, ? super Throwable> action) {
                        throw new IllegalStateException("cannot observe"); //$NON-NLS-1$
                    }
                };
        ToolApprover approver = (call, definition, cancellation) -> hostile;

        try (AgentRuntime runtime = runtime(toolCallingModel(), echoTools(),
                AgentEventListener.NOOP, approver)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);

            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(AgentError.Code.TOOL_APPROVAL, result.error().orElseThrow().code());
            assertEquals(0, runtime.activeRunCount());
        }
    }

    @Test
    public void cancellationDetachesNonCancellablePendingApprovalAndIgnoresLateDecision()
            throws Exception {
        CompletableFuture<ToolApprover.Decision> pending = new CompletableFuture<>();
        AtomicInteger executions = new AtomicInteger();
        CancellationSource source = new CancellationSource();
        ToolRuntime tools = tools((name, arguments, cancellation) -> {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
        });
        ToolApprover approver = (call, definition, cancellation) ->
                pending.minimalCompletionStage();

        try (AgentRuntime runtime = runtime(toolCallingModel(), tools,
                AgentEventListener.NOOP, approver)) {
            CompletableFuture<AgentResult> running = runtime.run(request(), source);
            assertFalse(running.isDone());

            source.cancel();
            AgentResult result = running.get(2, TimeUnit.SECONDS);

            assertEquals(AgentResult.Status.CANCELLED, result.status());
            assertEquals(AgentError.Code.CANCELLED, result.error().orElseThrow().code());
            assertFalse(pending.isDone());
            pending.complete(ToolApprover.Decision.ALLOW);
            assertEquals(0, executions.get());
            assertEquals(0, runtime.activeRunCount());
        }
    }

    @Test
    public void closeCancelsPendingApprovalAndPublishesClosedOnce() throws Exception {
        CompletableFuture<ToolApprover.Decision> pending = new CompletableFuture<>();
        List<String> events = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        ToolRuntime tools = tools((name, arguments, cancellation) -> {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
        });
        AgentRuntime runtime = runtime(toolCallingModel(), tools,
                recordingListener(events), (call, definition, cancellation) -> pending);
        CompletableFuture<AgentResult> running = runtime.run(request());
        assertFalse(running.isDone());

        runtime.close();
        AgentResult result = running.get(2, TimeUnit.SECONDS);

        assertEquals(AgentError.Code.CLOSED, result.error().orElseThrow().code());
        assertTrue(pending.isCancelled());
        assertEquals(0, executions.get());
        assertEquals(1, events.stream().filter(event -> event.startsWith("finished:")).count()); //$NON-NLS-1$
        assertEquals("finished:CANCELLED", events.get(events.size() - 1)); //$NON-NLS-1$
        assertEquals(0, runtime.activeRunCount());
    }

    @Test
    public void pendingApprovalDoesNotWeakenRuntimeOwnedFuture() {
        CompletableFuture<ToolApprover.Decision> pending = new CompletableFuture<>();
        AgentRuntime runtime = runtime(toolCallingModel(), echoTools(), AgentEventListener.NOOP,
                (call, definition, cancellation) -> pending);
        CompletableFuture<AgentResult> running = runtime.run(request());
        AgentResult fake = new AgentResult(AgentResult.Status.COMPLETED, Optional.of("fake"), //$NON-NLS-1$
                request().messages(), 0, Optional.empty());

        assertFalse(running.complete(fake));
        assertFalse(running.completeExceptionally(new IllegalStateException("fake"))); //$NON-NLS-1$
        try {
            running.orTimeout(1, TimeUnit.MILLISECONDS);
            throw new AssertionError("Expected timeout mutation to be rejected"); //$NON-NLS-1$
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("AgentRuntime")); //$NON-NLS-1$
        }
        assertFalse(running.isDone());
        assertEquals(1, runtime.activeRunCount());

        assertTrue(running.cancel(true));
        assertTrue(running.isCancelled());
        assertTrue(pending.isCancelled());
        assertEquals(0, runtime.activeRunCount());
        runtime.close();
    }

    @Test
    public void duplicateIdsFailBeforeAssistantOrApprovalEvents() throws Exception {
        AtomicInteger approvals = new AtomicInteger();
        List<String> events = new ArrayList<>();
        AgentModel model = (request, cancellation) -> CompletableFuture.completedFuture(
                AgentMessage.Assistant.tools(List.of(
                        new ToolCall("duplicate", "echo", "{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new ToolCall("duplicate", "echo", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ToolApprover approver = (call, definition, cancellation) -> {
            approvals.incrementAndGet();
            return CompletableFuture.completedFuture(ToolApprover.Decision.ALLOW);
        };

        try (AgentRuntime runtime = runtime(model, echoTools(), recordingListener(events), approver)) {
            AgentResult result = runtime.run(request()).get(2, TimeUnit.SECONDS);

            assertEquals(AgentError.Code.PROVIDER_RESPONSE, result.error().orElseThrow().code());
            assertEquals(0, approvals.get());
            assertEquals(List.of("step:1", "finished:FAILED"), events); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void toolDefinitionAnnotationsAreOptionalAndInputSchemaRemainsDefensive() {
        JsonObject schema = schema();
        ToolAnnotations hints = new ToolAnnotations("Delete file", true, false, true); //$NON-NLS-1$
        ToolDefinition annotated = new ToolDefinition("delete", "test", schema, hints); //$NON-NLS-1$ //$NON-NLS-2$
        ToolDefinition plain = new ToolDefinition("read", "test", schema); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Optional.of(hints), annotated.annotations());
        assertEquals(Optional.empty(), plain.annotations());
        annotated.inputSchema().addProperty("mutated", true); //$NON-NLS-1$
        assertFalse(annotated.inputSchema().has("mutated")); //$NON-NLS-1$
    }

    private static AgentEventListener recordingListener(List<String> events) {
        return new AgentEventListener() {
            @Override public void onStepStarted(String operationId, int step) {
                events.add("step:" + step); //$NON-NLS-1$
            }

            @Override public void onAssistantTextDelta(
                    String operationId, int step, String delta) {
                events.add("text:" + step + ":" + delta); //$NON-NLS-1$ //$NON-NLS-2$
            }

            @Override public void onAssistantReasoningDelta(
                    String operationId, int step, String delta) {
                events.add("reasoning:" + step + ":" + delta); //$NON-NLS-1$ //$NON-NLS-2$
            }

            @Override public void onAssistantMessage(
                    String operationId, int step, AgentMessage.Assistant message) {
                events.add("assistant:" + step + ":" //$NON-NLS-1$ //$NON-NLS-2$
                        + message.text().orElse("tools")); //$NON-NLS-1$
            }

            @Override public void onToolCallStarted(String operationId, int step, ToolCall call) {
                events.add("tool-start:" + step + ":" + call.id()); //$NON-NLS-1$ //$NON-NLS-2$
            }

            @Override public void onToolCallResult(String operationId, int step,
                    ToolCall call, ToolExecutionResult result) {
                events.add("tool-result:" + step + ":" + call.id() + ":" + result.code()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            @Override public void onTurnFinished(String operationId, AgentResult result) {
                events.add("finished:" + result.status()); //$NON-NLS-1$
            }
        };
    }

    private static AgentModel toolCallingModel() {
        return (request, cancellation) -> CompletableFuture.completedFuture(
                AgentMessage.Assistant.tools(List.of(
                        new ToolCall("call", "echo", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static ToolRuntime echoTools() {
        return tools((name, arguments, cancellation) ->
                CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject())));
    }

    private static ToolRuntime emptyTools() {
        return new ToolRuntime() {
            @Override public List<ToolDefinition> tools() { return List.of(); }
            @Override public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String name, JsonObject arguments, CancellationToken cancellation) {
                throw new AssertionError("No tool should execute"); //$NON-NLS-1$
            }
        };
    }

    private static ToolRuntime tools(ToolExecutor executor) {
        ToolDefinition definition = new ToolDefinition("echo", "test", schema()); //$NON-NLS-1$ //$NON-NLS-2$
        return new ToolRuntime() {
            @Override public List<ToolDefinition> tools() { return List.of(definition); }
            @Override public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String name, JsonObject arguments, CancellationToken cancellation) {
                return executor.execute(name, arguments, cancellation);
            }
        };
    }

    private static AgentRuntime runtime(AgentModel model, ToolRuntime tools,
            AgentEventListener listener, ToolApprover approver) {
        return new AgentRuntime(model, tools,
                new AgentRunConfig(4, Duration.ofSeconds(5)), listener, approver);
    }

    private static AgentRuntime streamingRuntime(StreamingAgentModel model, ToolRuntime tools,
            AgentEventListener listener, ToolApprover approver) {
        return new AgentRuntime(model, tools,
                new AgentRunConfig(4, Duration.ofSeconds(5)), listener, approver,
                AgentCompletionMode.STREAMING);
    }

    private static AgentRequest request() {
        return new AgentRequest("spi-test", List.of( //$NON-NLS-1$
                new AgentMessage.Text(AgentMessage.Role.USER, "test"))); //$NON-NLS-1$
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.add("properties", new JsonObject()); //$NON-NLS-1$
        return schema;
    }

    @FunctionalInterface
    private interface ToolExecutor {
        java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                String name, JsonObject arguments, CancellationToken cancellation);
    }
}
