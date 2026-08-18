/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.broker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.runtime.agent.AgentCompletionMode;
import com.codepilot1c.runtime.agent.AgentError;
import com.codepilot1c.runtime.agent.AgentEventListener;
import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentModel;
import com.codepilot1c.runtime.agent.AgentModelException;
import com.codepilot1c.runtime.agent.AgentRequest;
import com.codepilot1c.runtime.agent.AgentResult;
import com.codepilot1c.runtime.agent.AgentRunConfig;
import com.codepilot1c.runtime.agent.AgentRuntime;
import com.codepilot1c.runtime.agent.CancellationToken;
import com.codepilot1c.runtime.agent.StreamObserver;
import com.codepilot1c.runtime.agent.StreamingAgentModel;
import com.codepilot1c.runtime.agent.ToolApprover;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolExecutionResult;
import com.codepilot1c.runtime.agent.ToolRuntime;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Local-HTTP contract tests for the connected shell broker adapter. */
public class BrokerClientHttpTest {
    private static final String TOKEN = "broker-test-secret";
    private static final String CAPABILITIES = """
            {"schemaVersion":1,"maxSchemaVersion":1,"chat":true,"streaming":true,
             "provider":{"id":"active","name":"Active Provider","type":"openai_compatible",
                         "model":"safe-model","streamingEnabled":true}}
            """;

    @Test
    public void probesAllowlistedBrokerInfoAndSendsResolvedBearer() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            json(exchange, 200, CAPABILITIES);
        }, exchange -> json(exchange, 500, "{}"));
             BrokerClient client = fixture.client(TOKEN)) {
            BrokerInfo info = client.probe().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(1, info.schemaVersion());
            assertEquals(1, info.maxSchemaVersion());
            assertTrue(info.chat());
            assertTrue(info.streaming());
            assertEquals("active", info.provider().id());
            assertEquals("safe-model", info.provider().model());
            assertTrue(info.provider().streamingEnabled());
            assertEquals("Bearer " + TOKEN, authorization.get());
        }
    }

    @Test
    public void convertsEveryEventAndFragmentedFramesIntoObserverAndAssistant() throws Exception {
        AtomicReference<JsonObject> requestJson = new AtomicReference<>();
        String stream = ": keepalive\r\n\r\n"
                + event("delta", "{\"schemaVersion\":1,\"text\":\"Hel\"}")
                + event("reasoning", "{\"schemaVersion\":1,\"text\":\"think\"}")
                + ": another keepalive\n\n"
                + event("delta", "{\"schemaVersion\":1,\"text\":\"lo\"}")
                + event("tool_calls", "{\"schemaVersion\":1,\"toolCalls\":["
                        + "{\"id\":\"call-1\",\"name\":\"lookup\",\"arguments\":"
                        + "{\"path\":\"src/Каталог\",\"deep\":true},\"argumentsRepaired\":false}]}")
                + event("usage", "{\"schemaVersion\":1,\"promptTokens\":8,"
                        + "\"completionTokens\":3,\"totalTokens\":11,"
                        + "\"cacheReadInputTokens\":2,\"cacheCreationInputTokens\":1}")
                + event("done", "{\"schemaVersion\":1,\"finishReason\":\"tool_use\"}");
        try (Fixture fixture = new Fixture(exchange -> json(exchange, 200, CAPABILITIES), exchange -> {
            requestJson.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            fragmentedSse(exchange, stream, 3);
        }); BrokerClient client = fixture.client(TOKEN)) {
            List<String> observed = new CopyOnWriteArrayList<>();
            StreamObserver observer = new StreamObserver() {
                @Override public void onAssistantDelta(String delta) { observed.add("text:" + delta); }
                @Override public void onReasoningDelta(String delta) { observed.add("reasoning:" + delta); }
            };
            AgentMessage.Assistant assistant = new BrokeredAgentModel(client)
                    .complete(fullRequest(), CancellationToken.none(), observer)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(List.of("text:Hel", "reasoning:think", "text:lo"), observed);
            assertEquals(Optional.of("Hello"), assistant.text());
            assertEquals(1, assistant.toolCalls().size());
            assertEquals("call-1", assistant.toolCalls().get(0).id());
            assertEquals("lookup", assistant.toolCalls().get(0).name());
            assertEquals("src/Каталог", JsonParser.parseString(
                    assistant.toolCalls().get(0).argumentsJson()).getAsJsonObject()
                    .get("path").getAsString());

            JsonObject wire = requestJson.get();
            assertEquals(1, wire.get("schemaVersion").getAsInt());
            assertFalse(wire.has("model"));
            assertEquals("system", wire.getAsJsonArray("messages").get(0)
                    .getAsJsonObject().get("role").getAsString());
            assertEquals("call-old", wire.getAsJsonArray("messages").get(2)
                    .getAsJsonObject().getAsJsonArray("toolCalls").get(0)
                    .getAsJsonObject().get("id").getAsString());
            assertEquals("call-old", wire.getAsJsonArray("messages").get(3)
                    .getAsJsonObject().get("toolCallId").getAsString());
            assertEquals("object", wire.getAsJsonArray("tools").get(0)
                    .getAsJsonObject().getAsJsonObject("inputSchema")
                    .get("type").getAsString());
        }
    }

    @Test
    public void mapsBothAuthenticationStatusesToProviderAuth() throws Exception {
        for (int status : List.of(401, 403)) {
            try (Fixture fixture = new Fixture(exchange -> json(exchange, status,
                    "{\"error\":\"denied-" + TOKEN + "\"}"), exchange -> json(exchange, status,
                    "{\"error\":\"denied-" + TOKEN + "\"}"));
                 BrokerClient client = fixture.client(TOKEN);
                 AgentRuntime runtime = runtime(new BrokeredAgentModel(client))) {
                AgentResult result = runtime.run(new AgentRequest("auth", List.of(
                        new AgentMessage.Text(AgentMessage.Role.USER, "hello"))))
                        .get(2, TimeUnit.SECONDS);

                assertEquals(AgentResult.Status.FAILED, result.status());
                assertEquals(AgentError.Code.PROVIDER_AUTH, result.error().orElseThrow().code());
                assertFalse(result.error().orElseThrow().message().contains(TOKEN));
            }
        }
    }

    @Test
    public void mapsBusyAndUnavailableToTypedActionableBodySafeFailures() throws Exception {
        assertHttpFailure(409, "retry", "busy-body-" + TOKEN);
        assertHttpFailure(503, "configure a provider in EDT", "provider-body-" + TOKEN);
    }

    @Test
    public void rejectsMalformedSchemaAndUnknownEventsWithoutResponseBodyLeakage() throws Exception {
        String secret = "response-only-secret";
        String badSchema = event("delta", "{\"schemaVersion\":2,\"text\":\"" + secret + "\"}");
        assertStreamFailure(badSchema, AgentModelException.Kind.MALFORMED_RESPONSE, secret);

        String unknown = event("mystery", "{\"schemaVersion\":1,\"value\":\"" + secret + "\"}");
        assertStreamFailure(unknown, AgentModelException.Kind.MALFORMED_RESPONSE, secret);
    }

    @Test
    public void mapsMidstreamErrorAndDisconnectToBodySafeTransportFailures() throws Exception {
        String secret = "provider-diagnostic-secret";
        String error = event("delta", "{\"schemaVersion\":1,\"text\":\"prefix\"}")
                + event("error", "{\"schemaVersion\":1,\"code\":\"provider_error\","
                        + "\"message\":\"" + secret + "\"}");
        assertStreamFailure(error, AgentModelException.Kind.TRANSPORT, secret);

        String disconnected = event("delta", "{\"schemaVersion\":1,\"text\":\"partial-"
                + secret + "\"}");
        assertStreamFailure(disconnected, AgentModelException.Kind.TRANSPORT, secret);
    }

    @Test
    public void cancellationClosesTheOwnedHttpStream() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(exchange -> json(exchange, 200, CAPABILITIES), exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                started.countDown();
                while (true) {
                    output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (IOException expectedDisconnect) {
                disconnected.countDown();
            }
        }); BrokerClient client = fixture.client(TOKEN)) {
            TestCancellation cancellation = new TestCancellation();
            CompletableFuture<AgentMessage.Assistant> future = new BrokeredAgentModel(client)
                    .complete(simpleRequest(), cancellation, StreamObserver.NOOP).toCompletableFuture();
            assertTrue(started.await(2, TimeUnit.SECONDS));

            cancellation.cancel();

            assertCancelled(future);
            assertTrue("server did not observe the closed response stream",
                    disconnected.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void transportFailureAndClosedClientNeverExposeBearer() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:1/mcp");
        try (BrokerClient client = new BrokerClient(HttpClient.newHttpClient(), endpoint,
                TOKEN.toCharArray(), false, Duration.ofMillis(200))) {
            Throwable failure = failure(client.probe().toCompletableFuture());
            assertFalse(deepMessage(failure).contains(TOKEN));
            assertTrue(failure instanceof AgentModelException);
            assertEquals(AgentModelException.Kind.TRANSPORT,
                    ((AgentModelException) failure).kind());
        }
    }

    private static void assertHttpFailure(int status, String expectedMessage, String responseBody)
            throws Exception {
        try (Fixture fixture = new Fixture(exchange -> json(exchange, 200, CAPABILITIES),
                exchange -> json(exchange, status, responseBody));
             BrokerClient client = fixture.client(TOKEN)) {
            Throwable failure = failure(new BrokeredAgentModel(client)
                    .complete(simpleRequest(), CancellationToken.none(), StreamObserver.NOOP)
                    .toCompletableFuture());
            assertTrue(failure instanceof AgentModelException);
            AgentModelException typed = (AgentModelException) failure;
            assertEquals(AgentModelException.Kind.HTTP, typed.kind());
            assertEquals(status, typed.httpStatus());
            assertTrue(typed.getMessage().contains(expectedMessage));
            assertFalse(deepMessage(typed).contains(responseBody));
            assertFalse(deepMessage(typed).contains(TOKEN));
        }
    }

    private static void assertStreamFailure(String stream, AgentModelException.Kind kind, String secret)
            throws Exception {
        try (Fixture fixture = new Fixture(exchange -> json(exchange, 200, CAPABILITIES),
                exchange -> fragmentedSse(exchange, stream, 2));
             BrokerClient client = fixture.client(TOKEN)) {
            Throwable failure = failure(new BrokeredAgentModel(client)
                    .complete(simpleRequest(), CancellationToken.none(), StreamObserver.NOOP)
                    .toCompletableFuture());
            assertTrue(failure instanceof AgentModelException);
            assertEquals(kind, ((AgentModelException) failure).kind());
            assertFalse(deepMessage(failure).contains(secret));
            assertFalse(deepMessage(failure).contains(TOKEN));
        }
    }

    private static AgentModel.Request simpleRequest() {
        return new AgentModel.Request(List.of(
                new AgentMessage.Text(AgentMessage.Role.USER, "hello")), List.of());
    }

    private static AgentModel.Request fullRequest() {
        JsonObject schema = JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject();
        AgentMessage.Assistant prior = new AgentMessage.Assistant(Optional.of("checking"), List.of(
                new com.codepilot1c.runtime.agent.ToolCall("call-old", "lookup", "{\"old\":true}")));
        AgentMessage.Tool result = new AgentMessage.Tool("call-old", "lookup",
                ToolExecutionResult.success(JsonParser.parseString("{\"found\":true}")));
        return new AgentModel.Request(List.of(
                new AgentMessage.Text(AgentMessage.Role.SYSTEM, "be exact"),
                new AgentMessage.Text(AgentMessage.Role.USER, "hello"), prior, result),
                List.of(new ToolDefinition("lookup", "Lookup", schema)));
    }

    private static AgentRuntime runtime(StreamingAgentModel model) {
        ToolRuntime tools = new ToolRuntime() {
            @Override public List<ToolDefinition> tools() { return List.of(); }
            @Override public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String name, JsonObject arguments, CancellationToken cancellation) {
                return CompletableFuture.failedFuture(new AssertionError("unexpected tool execution"));
            }
        };
        return new AgentRuntime(model, tools, new AgentRunConfig(1, Duration.ofSeconds(2)),
                AgentEventListener.NOOP, ToolApprover.ALLOW_ALL,
                AgentCompletionMode.STREAMING);
    }

    private static String event(String type, String data) {
        return "event: " + type + "\n" + "data: " + data + "\n\n";
    }

    private static void fragmentedSse(HttpExchange exchange, String value, int fragmentBytes)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = exchange.getResponseBody()) {
            for (int offset = 0; offset < bytes.length; offset += fragmentBytes) {
                int count = Math.min(fragmentBytes, bytes.length - offset);
                output.write(bytes, offset, count);
                output.flush();
            }
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            fail("expected failure");
            throw new AssertionError("unreachable");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static void assertCancelled(CompletableFuture<?> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (java.util.concurrent.CancellationException expected) {
            assertTrue(future.isCancelled());
        }
    }

    private static String deepMessage(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable value = failure; value != null; value = value.getCause()) {
            if (value.getMessage() != null) text.append(value.getMessage());
        }
        return text.toString();
    }

    private static final class TestCancellation implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Runnable> actions = new ArrayList<>();

        @Override public boolean isCancelled() { return cancelled.get(); }

        @Override public Registration onCancel(Runnable action) {
            synchronized (actions) {
                if (cancelled.get()) action.run();
                else actions.add(action);
            }
            return () -> {
                synchronized (actions) { actions.remove(action); }
            };
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            List<Runnable> snapshot;
            synchronized (actions) { snapshot = List.copyOf(actions); }
            snapshot.forEach(Runnable::run);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;

        Fixture(HttpHandler capabilities, HttpHandler chat) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/llm/v1/capabilities", capabilities);
            server.createContext("/llm/v1/chat", chat);
            server.start();
        }

        BrokerClient client(String token) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            return new BrokerClient(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build(), endpoint,
                    token == null ? null : token.toCharArray(), false, Duration.ofSeconds(5));
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
