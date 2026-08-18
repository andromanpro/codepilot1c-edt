/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.runtime.provider.ProviderConfiguration;
import com.codepilot1c.runtime.provider.RuntimeProviderFactory;
import com.codepilot1c.runtime.spi.LogSink;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Executable provider-to-agent vertical slice against a local HTTP server. */
public class OpenAiAgentHttpTest {

    @Test
    public void serializesToolsExecutesMultipleCallsAndReturnsFinalText() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        List<JsonObject> bodies = new ArrayList<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
            bodies.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            if (requests.getAndIncrement() == 0) {
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"one","type":"function","function":{"name":"echo","arguments":"{\\\"value\\\":1}"}},
                          {"id":"two","type":"function","function":{"name":"echo","arguments":"{\\\"value\\\":2}"}}
                        ]}}]}
                        """);
            } else {
                respond(exchange, 200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"all done\"}}]}"); //$NON-NLS-1$
            }
        });
        char[] apiKey = "http-secret".toCharArray(); //$NON-NLS-1$
        List<Integer> values = new ArrayList<>();
        try {
            OpenAiCompatibleAgentModel model = new OpenAiCompatibleAgentModel(
                    new RuntimeProviderFactory().create(configuration(server, apiKey)));
            ToolRuntime tools = singleTool((name, arguments, cancellation) -> {
                values.add(arguments.get("value").getAsInt()); //$NON-NLS-1$
                return CompletableFuture.completedFuture(ToolExecutionResult.success(arguments));
            });
            try (AgentRuntime runtime = new AgentRuntime(buffered(model), tools,
                    new AgentRunConfig(4, Duration.ofSeconds(3)))) {
                AgentResult result = runtime.run(new AgentRequest("http-op", List.of( //$NON-NLS-1$
                        new AgentMessage.Text(AgentMessage.Role.USER, "run")))) //$NON-NLS-1$
                        .get(3, TimeUnit.SECONDS);

                assertEquals(AgentResult.Status.COMPLETED, result.status());
                assertEquals("all done", result.text().orElseThrow()); //$NON-NLS-1$
                assertEquals(List.of(1, 2), values);
            }
            assertEquals("Bearer http-secret", authorization.get()); //$NON-NLS-1$
            assertEquals(2, bodies.size());
            assertEquals("test-model", bodies.get(0).get("model").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, bodies.get(0).getAsJsonArray("tools").size()); //$NON-NLS-1$
            assertEquals(4, bodies.get(1).getAsJsonArray("messages").size()); //$NON-NLS-1$
            assertEquals("tool", bodies.get(1).getAsJsonArray("messages").get(2) //$NON-NLS-1$ //$NON-NLS-2$
                    .getAsJsonObject().get("role").getAsString()); //$NON-NLS-1$
            assertFalse(bodies.get(1).getAsJsonArray("messages").get(2) //$NON-NLS-1$
                    .getAsJsonObject().has("name")); //$NON-NLS-1$
        } finally {
            java.util.Arrays.fill(apiKey, '\0');
            server.stop(0);
        }
    }

    @Test
    public void malformedModelJsonReturnsTypedErrorWithoutLeakingBody() throws Exception {
        String secretBody = "provider-body-secret"; //$NON-NLS-1$
        HttpServer server = server(exchange -> respond(exchange, 200, "not-json-" + secretBody)); //$NON-NLS-1$
        List<LogSink.Event> events = new ArrayList<>();
        try {
            OpenAiCompatibleAgentModel model = new OpenAiCompatibleAgentModel(
                    new RuntimeProviderFactory().create(configuration(server, null)));
            try (AgentRuntime runtime = new AgentRuntime(buffered(model), singleTool((name, args, cancellation) ->
                    CompletableFuture.completedFuture(ToolExecutionResult.success(args))),
                    new AgentRunConfig(2, Duration.ofSeconds(2)), events::add)) {
                AgentResult result = runtime.run(new AgentRequest("malformed-op", List.of( //$NON-NLS-1$
                        new AgentMessage.Text(AgentMessage.Role.USER, "request-secret")))) //$NON-NLS-1$
                        .get(2, TimeUnit.SECONDS);

                assertEquals(AgentResult.Status.FAILED, result.status());
                assertEquals(AgentError.Code.PROVIDER_RESPONSE, result.error().orElseThrow().code());
                assertEquals("Provider response is malformed", result.error().orElseThrow().message()); //$NON-NLS-1$
            }
            String logs = events.stream().map(LogSink.Event::message).reduce("", String::concat); //$NON-NLS-1$
            assertFalse(logs.contains(secretBody));
            assertFalse(logs.contains("request-secret")); //$NON-NLS-1$
            assertTrue(events.stream().noneMatch(event -> event.cause().isPresent()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void cancellationDuringInFlightHttpReturnsCancelledResult() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            requestStarted.countDown();
            try {
                releaseServer.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        CancellationSource source = new CancellationSource();
        try {
            OpenAiCompatibleAgentModel model = new OpenAiCompatibleAgentModel(
                    new RuntimeProviderFactory().create(configuration(server, null)));
            try (AgentRuntime runtime = new AgentRuntime(buffered(model),
                    singleTool((name, args, cancellation) ->
                            CompletableFuture.completedFuture(ToolExecutionResult.success(args))),
                    new AgentRunConfig(2, Duration.ofSeconds(3)))) {
                CompletableFuture<AgentResult> running = runtime.run(new AgentRequest("cancel-http", List.of( //$NON-NLS-1$
                        new AgentMessage.Text(AgentMessage.Role.USER, "wait"))), source); //$NON-NLS-1$
                assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
                source.cancel();

                AgentResult result = running.get(2, TimeUnit.SECONDS);
                assertEquals(AgentResult.Status.CANCELLED, result.status());
                assertEquals(AgentError.Code.CANCELLED, result.error().orElseThrow().code());
            }
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    @Test
    public void rejectsUnexpectedMessageRoleAndToolCallType() throws Exception {
        assertProviderResponseError(
                "{\"choices\":[{\"message\":{\"role\":\"user\",\"content\":\"wrong\"}}]}"); //$NON-NLS-1$
        assertProviderResponseError("""
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call","type":"custom","function":{"name":"echo","arguments":"{}"}}
                ]}}]}
                """);
    }

    private static void assertProviderResponseError(String responseBody) throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, responseBody));
        try {
            OpenAiCompatibleAgentModel model = new OpenAiCompatibleAgentModel(
                    new RuntimeProviderFactory().create(configuration(server, null)));
            try (AgentRuntime runtime = new AgentRuntime(buffered(model),
                    singleTool((name, args, cancellation) ->
                            CompletableFuture.completedFuture(ToolExecutionResult.success(args))),
                    new AgentRunConfig(2, Duration.ofSeconds(2)))) {
                AgentResult result = runtime.run(new AgentRequest("invalid-wire", List.of( //$NON-NLS-1$
                        new AgentMessage.Text(AgentMessage.Role.USER, "request")))) //$NON-NLS-1$
                        .get(2, TimeUnit.SECONDS);
                assertEquals(AgentResult.Status.FAILED, result.status());
                assertEquals(AgentError.Code.PROVIDER_RESPONSE, result.error().orElseThrow().code());
            }
        } finally {
            server.stop(0);
        }
    }

    private static ProviderConfiguration configuration(HttpServer server, char[] apiKey) {
        return ProviderConfiguration.builder()
                .id("local") //$NON-NLS-1$
                .displayName("Local") //$NON-NLS-1$
                .baseUri(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/")) //$NON-NLS-1$ //$NON-NLS-2$
                .defaultModel("test-model") //$NON-NLS-1$
                .requestTimeout(Duration.ofSeconds(2))
                .apiKey(apiKey)
                .build();
    }

    private static AgentModel buffered(OpenAiCompatibleAgentModel model) {
        return model::complete;
    }

    private static ToolRuntime singleTool(ToolExecutor executor) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject properties = new JsonObject();
        JsonObject value = new JsonObject();
        value.addProperty("type", "integer"); //$NON-NLS-1$ //$NON-NLS-2$
        properties.add("value", value); //$NON-NLS-1$
        schema.add("properties", properties); //$NON-NLS-1$
        schema.add("required", JsonParser.parseString("[\"value\"]")); //$NON-NLS-1$ //$NON-NLS-2$
        List<ToolDefinition> definitions = List.of(new ToolDefinition("echo", "Echo value", schema)); //$NON-NLS-1$ //$NON-NLS-2$
        return new ToolRuntime() {
            @Override public List<ToolDefinition> tools() { return definitions; }
            @Override public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String name, JsonObject arguments, CancellationToken cancellation) {
                return executor.execute(name, arguments, cancellation);
            }
        };
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); //$NON-NLS-1$
        server.createContext("/v1/chat/completions", handler::handle); //$NON-NLS-1$
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    private interface ToolExecutor {
        java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                String name, JsonObject arguments, CancellationToken cancellation);
    }
}
