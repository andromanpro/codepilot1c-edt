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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.codepilot1c.runtime.spi.LogSink;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AgentRuntimeTest {

    @Test
    public void completesOrdinaryTextResponse() throws Exception {
        AgentModel model = (request, cancellation) ->
                CompletableFuture.completedFuture(AgentMessage.Assistant.text("done")); //$NON-NLS-1$
        try (AgentRuntime runtime = runtime(model, emptyTools(), 3, Duration.ofSeconds(2))) {
            AgentResult result = runtime.run(request("hello")).get(2, TimeUnit.SECONDS); //$NON-NLS-1$

            assertEquals(AgentResult.Status.COMPLETED, result.status());
            assertEquals(Optional.of("done"), result.text()); //$NON-NLS-1$
            assertEquals(1, result.steps());
            assertEquals(2, result.transcript().size());
        }
    }

    @Test
    public void executesMultipleToolCallsInAssistantOrder() throws Exception {
        AtomicInteger turn = new AtomicInteger();
        List<String> executed = new ArrayList<>();
        ToolRuntime tools = tools(List.of("echo"), (name, arguments, cancellation) -> { //$NON-NLS-1$
            executed.add(arguments.get("value").getAsString()); //$NON-NLS-1$
            JsonObject data = new JsonObject();
            data.addProperty("value", arguments.get("value").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            return CompletableFuture.completedFuture(ToolExecutionResult.success(data));
        });
        AgentModel model = (modelRequest, cancellation) -> turn.getAndIncrement() == 0
                ? CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                        new ToolCall("call-1", "echo", "{\"value\":\"first\"}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new ToolCall("call-2", "echo", "{\"value\":\"second\"}")))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : CompletableFuture.completedFuture(AgentMessage.Assistant.text("finished")); //$NON-NLS-1$

        try (AgentRuntime runtime = runtime(model, tools, 4, Duration.ofSeconds(2))) {
            AgentResult result = runtime.run(request("use tools")).get(2, TimeUnit.SECONDS); //$NON-NLS-1$

            assertEquals(AgentResult.Status.COMPLETED, result.status());
            assertEquals(List.of("first", "second"), executed); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(5, result.transcript().size());
            assertTrue(result.transcript().get(2) instanceof AgentMessage.Tool);
            assertTrue(result.transcript().get(3) instanceof AgentMessage.Tool);
        }
    }

    @Test
    public void invalidNonObjectAndUnknownToolCallsBecomeStructuredResults() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        ToolRuntime tools = tools(List.of("known"), (name, arguments, cancellation) -> { //$NON-NLS-1$
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
        });
        AgentModel model = (modelRequest, cancellation) -> {
            if (turns.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                        new ToolCall("bad-json", "known", "{oops"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new ToolCall("non-object", "known", "[1,2]"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new ToolCall("unknown", "missing", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            List<AgentMessage.Tool> results = modelRequest.messages().stream()
                    .filter(AgentMessage.Tool.class::isInstance)
                    .map(AgentMessage.Tool.class::cast).toList();
            assertEquals(List.of("MALFORMED_ARGUMENTS", "MALFORMED_ARGUMENTS", "UNKNOWN_TOOL"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    results.stream().map(tool -> tool.result().code()).toList());
            return CompletableFuture.completedFuture(AgentMessage.Assistant.text("recovered")); //$NON-NLS-1$
        };

        try (AgentRuntime runtime = runtime(model, tools, 3, Duration.ofSeconds(2))) {
            AgentResult result = runtime.run(request("bad calls")).get(2, TimeUnit.SECONDS); //$NON-NLS-1$
            assertEquals(AgentResult.Status.COMPLETED, result.status());
            assertEquals(0, executions.get());
        }
    }

    @Test
    public void toolErrorIsReturnedToModelWithoutFailingRun() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        ToolRuntime tools = tools(List.of("fails"), (name, arguments, cancellation) -> //$NON-NLS-1$
                CompletableFuture.completedFuture(ToolExecutionResult.failure(
                        "TOOL_REJECTED", "Request was rejected"))); //$NON-NLS-1$ //$NON-NLS-2$
        AgentModel model = (modelRequest, cancellation) -> {
            if (turns.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                        new ToolCall("call", "fails", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            AgentMessage.Tool result = (AgentMessage.Tool) modelRequest.messages()
                    .get(modelRequest.messages().size() - 1);
            assertTrue(result.result().error());
            assertEquals("TOOL_REJECTED", result.result().code()); //$NON-NLS-1$
            return CompletableFuture.completedFuture(AgentMessage.Assistant.text("handled")); //$NON-NLS-1$
        };

        try (AgentRuntime runtime = runtime(model, tools, 3, Duration.ofSeconds(2))) {
            assertEquals(AgentResult.Status.COMPLETED,
                    runtime.run(request("fail safely")).get(2, TimeUnit.SECONDS).status()); //$NON-NLS-1$
        }
    }

    @Test
    public void duplicateToolCallIdsAreMalformedProviderResponse() throws Exception {
        ToolRuntime tools = tools(List.of("echo"), (name, arguments, cancellation) -> //$NON-NLS-1$
                CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject())));
        AgentModel model = (request, cancellation) -> CompletableFuture.completedFuture(
                AgentMessage.Assistant.tools(List.of(
                        new ToolCall("same", "echo", "{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new ToolCall("same", "echo", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try (AgentRuntime runtime = runtime(model, tools, 2, Duration.ofSeconds(2))) {
            AgentResult result = runtime.run(request("duplicate")).get(2, TimeUnit.SECONDS); //$NON-NLS-1$
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(AgentError.Code.PROVIDER_RESPONSE, result.error().orElseThrow().code());
            assertEquals(1, result.transcript().size());
        }
    }

    @Test
    public void stopsAtConfiguredStepLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AgentModel model = (request, cancellation) -> CompletableFuture.completedFuture(
                AgentMessage.Assistant.tools(List.of(new ToolCall(
                        "call-" + calls.incrementAndGet(), "echo", "{}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ToolRuntime tools = tools(List.of("echo"), (name, arguments, cancellation) -> { //$NON-NLS-1$
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
        });

        try (AgentRuntime runtime = runtime(model, tools, 2, Duration.ofSeconds(2))) {
            AgentResult result = runtime.run(request("loop")).get(2, TimeUnit.SECONDS); //$NON-NLS-1$
            assertEquals(AgentResult.Status.STEP_LIMIT, result.status());
            assertEquals(AgentError.Code.STEP_LIMIT, result.error().orElseThrow().code());
            assertEquals(2, result.steps());
            assertEquals(2, executions.get());
        }
    }

    @Test
    public void cancelsInFlightProviderFuture() throws Exception {
        CompletableFuture<AgentMessage.Assistant> inFlight = new CompletableFuture<>();
        AgentModel model = (request, cancellation) -> inFlight;
        CancellationSource source = new CancellationSource();
        try (AgentRuntime runtime = runtime(model, emptyTools(), 2, Duration.ofSeconds(5))) {
            CompletableFuture<AgentResult> running = runtime.run(request("wait"), source); //$NON-NLS-1$
            source.cancel();
            AgentResult result = running.get(2, TimeUnit.SECONDS);

            assertEquals(AgentResult.Status.CANCELLED, result.status());
            assertEquals(AgentError.Code.CANCELLED, result.error().orElseThrow().code());
            assertTrue(inFlight.isCancelled());
        }
    }

    @Test
    public void timesOutInFlightProviderFuture() throws Exception {
        CompletableFuture<AgentMessage.Assistant> inFlight = new CompletableFuture<>();
        AgentModel model = (request, cancellation) -> inFlight;
        try (AgentRuntime runtime = runtime(model, emptyTools(), 2, Duration.ofMillis(40))) {
            AgentResult result = runtime.run(request("wait")).get(2, TimeUnit.SECONDS); //$NON-NLS-1$

            assertEquals(AgentResult.Status.TIMED_OUT, result.status());
            assertEquals(AgentError.Code.TIMEOUT, result.error().orElseThrow().code());
            assertTrue(inFlight.isCancelled());
        }
    }

    @Test
    public void logsNeverContainMessagesArgumentsResultsOrFailureCauses() throws Exception {
        String secret = "super-secret-value"; //$NON-NLS-1$
        List<LogSink.Event> events = new ArrayList<>();
        AtomicInteger turns = new AtomicInteger();
        AgentModel model = (modelRequest, cancellation) -> {
            if (turns.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                        new ToolCall("call", "secret-tool", //$NON-NLS-1$ //$NON-NLS-2$
                                "{\"password\":\"" + secret + "\"}")))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return CompletableFuture.failedFuture(new AgentModelException(
                    AgentModelException.Kind.TRANSPORT, secret));
        };
        ToolRuntime tools = tools(List.of("secret-tool"), (name, arguments, cancellation) -> //$NON-NLS-1$
                CompletableFuture.completedFuture(ToolExecutionResult.failure(
                        "DENIED", secret, JsonParser.parseString("{\"secret\":\"" + secret + "\"}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        try (AgentRuntime runtime = new AgentRuntime(model, tools,
                new AgentRunConfig(3, Duration.ofSeconds(2)), events::add)) {
            AgentResult result = runtime.run(request(secret)).get(2, TimeUnit.SECONDS);
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals("Provider request failed", result.error().orElseThrow().message()); //$NON-NLS-1$
        }
        String logged = events.stream().map(LogSink.Event::message)
                .reduce("", (left, right) -> left + right); //$NON-NLS-1$
        assertFalse(logged.contains(secret));
        assertFalse(events.stream().anyMatch(event -> event.cause().isPresent()));
    }

    private static AgentRequest request(String text) {
        return new AgentRequest("test-op", List.of(new AgentMessage.Text(AgentMessage.Role.USER, text))); //$NON-NLS-1$
    }

    private static AgentRuntime runtime(AgentModel model, ToolRuntime tools, int steps, Duration timeout) {
        return new AgentRuntime(model, tools, new AgentRunConfig(steps, timeout));
    }

    private static ToolRuntime emptyTools() {
        return tools(List.of(), (name, arguments, cancellation) ->
                CompletableFuture.completedFuture(ToolExecutionResult.failure("UNKNOWN", "Unknown"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ToolRuntime tools(List<String> names, ToolExecutor executor) {
        List<ToolDefinition> definitions = names.stream()
                .map(name -> new ToolDefinition(name, "test", schema())) //$NON-NLS-1$
                .toList();
        return new ToolRuntime() {
            @Override public List<ToolDefinition> tools() { return definitions; }
            @Override public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String name, JsonObject arguments, CancellationToken cancellation) {
                return executor.execute(name, arguments, cancellation);
            }
        };
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
