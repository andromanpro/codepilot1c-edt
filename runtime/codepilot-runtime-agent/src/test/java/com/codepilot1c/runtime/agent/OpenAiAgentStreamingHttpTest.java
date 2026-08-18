/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.codepilot1c.runtime.provider.ProviderConfiguration;
import com.codepilot1c.runtime.provider.RuntimeProviderFactory;
import com.codepilot1c.runtime.spi.LogSink;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Streaming provider-to-agent coverage against scripted local HTTP responses. */
public class OpenAiAgentStreamingHttpTest {

    @Test
    public void streamingAndBufferedAreGoldenEquivalentWithUsageInBothScripts() throws Exception {
        List<JsonObject> requests = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            JsonObject request = JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(request);
            if (!request.has("stream")) { //$NON-NLS-1$
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":"Hello world","tool_calls":[
                          {"id":"call-1","type":"function","function":{"name":"echo","arguments":"{\\"value\\":7}"}}
                        ]}}],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
                        """);
                return;
            }
            startStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, chunk("{\"content\":\"Hello \"}", null)); //$NON-NLS-1$
                writeEvent(output, chunk("{\"reasoning_content\":\"carefully\"}", null)); //$NON-NLS-1$
                writeEvent(output, chunk("{\"content\":\"world\"}", null)); //$NON-NLS-1$
                writeEvent(output, chunk("""
                        {"tool_calls":[{"index":0,"id":"call-","type":"function",
                          "function":{"name":"ec","arguments":"{\\"value\\":"}}]}
                        """, null));
                writeEvent(output, chunk("""
                        {"tool_calls":[{"index":0,"id":"1",
                          "function":{"name":"ho","arguments":"7}"}}]}
                        """, "tool_calls")); //$NON-NLS-1$
                writeEvent(output,
                        "{\"choices\":[],\"usage\":{\"prompt_tokens\":12," //$NON-NLS-1$
                        + "\"completion_tokens\":8,\"total_tokens\":20}}"); //$NON-NLS-1$
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            } finally {
                exchange.close();
            }
        });
        try {
            OpenAiCompatibleAgentModel model = model(server, null);
            AgentModel.Request request = request();
            AgentMessage.Assistant buffered = model.complete(request, CancellationToken.none())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            List<String> deltas = new CopyOnWriteArrayList<>();
            AgentMessage.Assistant streaming = model.complete(request, CancellationToken.none(),
                    new StreamObserver() {
                        @Override public void onTextDelta(String delta) {
                            deltas.add("text:" + delta); //$NON-NLS-1$
                        }
                        @Override public void onReasoningDelta(String delta) {
                            deltas.add("reasoning:" + delta); //$NON-NLS-1$
                        }
                    }).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(model instanceof StreamingAgentModel);
            assertEquals(buffered, streaming);
            assertEquals(AgentMessage.Assistant.class, streaming.getClass());
            assertEquals(List.of("text:Hello ", "reasoning:carefully", "text:world"), deltas); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(2, requests.size());
            assertFalse(requests.get(0).has("stream")); //$NON-NLS-1$
            assertTrue(requests.get(1).get("stream").getAsBoolean()); //$NON-NLS-1$
            assertTrue(requests.get(1).getAsJsonObject("stream_options") //$NON-NLS-1$
                    .get("include_usage").getAsBoolean()); //$NON-NLS-1$
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamingAuthenticationFailureMapsExplicitlyWithoutLeaks() throws Exception {
        String privateBody = "private-auth-response"; //$NON-NLS-1$
        String privateRequest = "private-auth-request"; //$NON-NLS-1$
        char[] secret = "private-api-key".toCharArray(); //$NON-NLS-1$
        HttpServer server = server(exchange -> respond(exchange, 401, privateBody));
        List<LogSink.Event> logs = new ArrayList<>();
        try {
            AgentResult result = run(server, secret, privateRequest, logs);

            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(AgentError.Code.PROVIDER_AUTH, result.error().orElseThrow().code());
            assertEquals("Provider authentication failed", result.error().orElseThrow().message()); //$NON-NLS-1$
            String diagnostics = diagnostics(result, logs);
            assertFalse(diagnostics.contains(privateBody));
            assertFalse(diagnostics.contains(privateRequest));
            assertFalse(diagnostics.contains(new String(secret)));
            assertTrue(logs.stream().noneMatch(event -> event.cause().isPresent()));
        } finally {
            Arrays.fill(secret, '\0');
            server.stop(0);
        }
    }

    @Test
    public void streamHttpProtocolAndTransportFailuresKeepTypedBodyFreeErrors() throws Exception {
        assertStreamFailure(exchange -> respond(exchange, 429, "private-http-body"), //$NON-NLS-1$
                AgentError.Code.PROVIDER_HTTP, "private-http-body"); //$NON-NLS-1$
        assertStreamFailure(exchange -> {
            startStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, "{\"private-protocol-body\":"); //$NON-NLS-1$
            } finally {
                exchange.close();
            }
        }, AgentError.Code.PROVIDER_RESPONSE, "private-protocol-body"); //$NON-NLS-1$
        assertStreamFailure(exchange -> {
            startStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, chunk("{\"content\":\"partial\"}", null)); //$NON-NLS-1$
            } finally {
                exchange.close();
            }
        }, AgentError.Code.PROVIDER_TRANSPORT, "partial"); //$NON-NLS-1$
    }

    @Test
    public void cancellationClosesOpenStreamAndSuppressesLaterDeltas() throws Exception {
        CountDownLatch firstSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            startStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, chunk("{\"content\":\"started\"}", null)); //$NON-NLS-1$
                firstSent.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    writeEvent(output, chunk("{\"content\":\"late\"}", null)); //$NON-NLS-1$
                    output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
                } catch (IOException closedByCancellation) {
                    // Expected when cancellation closes the response stream.
                }
            } finally {
                exchange.close();
                handlerFinished.countDown();
            }
        });
        CancellationSource cancellation = new CancellationSource();
        List<String> deltas = new CopyOnWriteArrayList<>();
        try {
            CompletableFuture<AgentMessage.Assistant> completion = model(server, null)
                    .complete(request(), cancellation, new StreamObserver() {
                        @Override public void onTextDelta(String delta) {
                            deltas.add(delta);
                        }
                    }).toCompletableFuture();
            assertTrue(firstSent.await(1, TimeUnit.SECONDS));
            awaitDelta(deltas);

            assertTrue(cancellation.cancel());
            assertCancelled(completion);
            release.countDown();
            assertTrue(handlerFinished.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("started"), deltas); //$NON-NLS-1$
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static void assertStreamFailure(
            ExchangeHandler handler, AgentError.Code expected, String forbidden) throws Exception {
        HttpServer server = server(handler);
        List<LogSink.Event> logs = new ArrayList<>();
        try {
            AgentResult result = run(server, null, "private-request", logs); //$NON-NLS-1$
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(expected, result.error().orElseThrow().code());
            assertFalse(diagnostics(result, logs).contains(forbidden));
            assertTrue(logs.stream().noneMatch(event -> event.cause().isPresent()));
        } finally {
            server.stop(0);
        }
    }

    private static AgentResult run(HttpServer server, char[] apiKey,
            String prompt, List<LogSink.Event> logs) throws Exception {
        try (AgentRuntime runtime = new AgentRuntime(model(server, apiKey), emptyTools(),
                new AgentRunConfig(2, Duration.ofSeconds(2)), logs::add,
                AgentEventListener.NOOP, ToolApprover.ALLOW_ALL,
                AgentCompletionMode.STREAMING)) {
            return runtime.run(new AgentRequest("stream-test", List.of( //$NON-NLS-1$
                    new AgentMessage.Text(AgentMessage.Role.USER, prompt))))
                    .get(2, TimeUnit.SECONDS);
        }
    }

    private static String diagnostics(AgentResult result, List<LogSink.Event> logs) {
        StringBuilder value = new StringBuilder(result.error().orElseThrow().toString());
        logs.forEach(event -> value.append(event.message()));
        return value.toString();
    }

    private static AgentModel.Request request() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        return new AgentModel.Request(List.of(
                new AgentMessage.Text(AgentMessage.Role.USER, "run")), //$NON-NLS-1$
                List.of(new ToolDefinition("echo", "Echo", schema))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ToolRuntime emptyTools() {
        return new ToolRuntime() {
            @Override public List<ToolDefinition> tools() {
                return List.of();
            }
            @Override public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String name, JsonObject arguments, CancellationToken cancellation) {
                throw new AssertionError("No tool should execute"); //$NON-NLS-1$
            }
        };
    }

    private static OpenAiCompatibleAgentModel model(HttpServer server, char[] apiKey) {
        ProviderConfiguration configuration = ProviderConfiguration.builder()
                .id("stream-agent") //$NON-NLS-1$
                .displayName("Stream agent") //$NON-NLS-1$
                .baseUri(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/")) //$NON-NLS-1$ //$NON-NLS-2$
                .defaultModel("test-model") //$NON-NLS-1$
                .requestTimeout(Duration.ofSeconds(2))
                .apiKey(apiKey)
                .build();
        return new OpenAiCompatibleAgentModel(new RuntimeProviderFactory().create(configuration));
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); //$NON-NLS-1$
        server.createContext("/v1/chat/completions", handler::handle); //$NON-NLS-1$
        server.start();
        return server;
    }

    private static void startStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(200, 0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void writeEvent(OutputStream output, String json) throws IOException {
        output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        output.flush();
    }

    private static String chunk(String delta, String finishReason) {
        String finish = finishReason == null ? "null" : "\"" + finishReason + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String singleLineDelta = delta.replace("\n", "").replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return "{\"choices\":[{\"index\":0,\"delta\":" + singleLineDelta //$NON-NLS-1$
                + ",\"finish_reason\":" + finish + "}]}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void awaitDelta(List<String> deltas) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (deltas.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(deltas.isEmpty());
    }

    private static void assertCancelled(CompletableFuture<?> future) throws Exception {
        try {
            future.get(1, TimeUnit.SECONDS);
            fail("Expected cancellation"); //$NON-NLS-1$
        } catch (CancellationException expected) {
            // Direct cancellation is permitted.
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof CancellationException);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
