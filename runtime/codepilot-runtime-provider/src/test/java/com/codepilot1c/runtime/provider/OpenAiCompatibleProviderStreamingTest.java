/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** End-to-end SSE coverage using the JDK's local HTTP server. */
public class OpenAiCompatibleProviderStreamingTest {

    @Test
    public void streamsTextReasoningFiveFragmentToolCallUsageAndDone() throws Exception {
        AtomicReference<JsonObject> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> clientHeader = new AtomicReference<>();
        CountDownLatch exchangeClosed = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            received.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
            clientHeader.set(exchange.getRequestHeaders().getFirst("X-Client")); //$NON-NLS-1$
            startEventStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, deltaChunk("Hello ", "think ")); //$NON-NLS-1$ //$NON-NLS-2$
                writeEvent(output, deltaChunk("world", "carefully")); //$NON-NLS-1$ //$NON-NLS-2$
                writeEvent(output, toolChunk(1, "call_", "get_", "{\"", null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                writeEvent(output, toolChunk(1, "42", "weather", "city", null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                writeEvent(output, toolChunk(1, null, null, "\":\"", null)); //$NON-NLS-1$
                writeEvent(output, toolChunk(1, null, null, "Moscow", null)); //$NON-NLS-1$
                writeEvent(output, toolChunk(1, null, null, "\"}", "tool_calls")); //$NON-NLS-1$ //$NON-NLS-2$
                writeEvent(output, usageChunk(12, 8, 20));
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            } finally {
                exchange.close();
                exchangeClosed.countDown();
            }
        });
        char[] secret = "stream-secret".toCharArray(); //$NON-NLS-1$
        try {
            OpenAiCompatibleProvider provider = provider(server, secret, Map.of("X-Client", "shell")); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject request = request();
            JsonObject callerCopy = request.deepCopy();
            List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();

            provider.stream(request, events::add).get(3, TimeUnit.SECONDS);

            assertEquals(callerCopy, request);
            assertTrue(received.get().get("stream").getAsBoolean()); //$NON-NLS-1$
            assertTrue(received.get().getAsJsonObject("stream_options") //$NON-NLS-1$
                    .get("include_usage").getAsBoolean()); //$NON-NLS-1$
            assertTrue(received.get().getAsJsonObject("stream_options") //$NON-NLS-1$
                    .get("custom_option").getAsBoolean()); //$NON-NLS-1$
            assertEquals("Bearer stream-secret", authorization.get()); //$NON-NLS-1$
            assertEquals("shell", clientHeader.get()); //$NON-NLS-1$
            assertEquals(List.of(
                    new ProviderStreamEvent.TextDelta("Hello "), //$NON-NLS-1$
                    new ProviderStreamEvent.ReasoningDelta("think "), //$NON-NLS-1$
                    new ProviderStreamEvent.TextDelta("world"), //$NON-NLS-1$
                    new ProviderStreamEvent.ReasoningDelta("carefully"), //$NON-NLS-1$
                    new ProviderStreamEvent.ToolCall(1, "call_42", "get_weather", //$NON-NLS-1$ //$NON-NLS-2$
                            "{\"city\":\"Moscow\"}"), //$NON-NLS-1$
                    new ProviderStreamEvent.Usage(12, 8, 20),
                    new ProviderStreamEvent.Done()), events);
            assertTrue(exchangeClosed.await(1, TimeUnit.SECONDS));
        } finally {
            Arrays.fill(secret, '\0');
            server.stop(0);
        }
    }

    @Test
    public void parsesCrLfMultilineEventFramesAndKeepalives() throws Exception {
        HttpServer server = server(exchange -> {
            startEventStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write((": keepalive\r\n\r\n"
                        + "event: completion\r\n"
                        + "data: {\"choices\":[\r\n"
                        + "data: {\"index\":0,\"delta\":{\"content\":\"multi\"},\"finish_reason\":null}\r\n"
                        + "data: ]}\r\n\r\n"
                        + "event: completion\r\n"
                        + "data: [DONE]\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        try {
            List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
            provider(server, null, Map.of()).streamRaw(request(), events::add)
                    .get(3, TimeUnit.SECONDS);

            assertEquals(List.of(new ProviderStreamEvent.TextDelta("multi"), //$NON-NLS-1$
                    new ProviderStreamEvent.Done()), events);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void malformedJsonIsTypedBodyFreeResponseFailure() throws Exception {
        String privateResponseData = "private-response-payload"; //$NON-NLS-1$
        HttpServer server = server(exchange -> {
            startEventStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("data: {\"choices\":" + privateResponseData + "}\n\n") //$NON-NLS-1$ //$NON-NLS-2$
                        .getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        try {
            assertFailure(provider(server, null, Map.of()).stream(request(), event -> { }),
                    ProviderStreamException.Kind.RESPONSE, privateResponseData);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void httpErrorIsTypedBodyFreeResponseFailureWithStatus() throws Exception {
        String privateResponseData = "private-http-response"; //$NON-NLS-1$
        HttpServer server = server(exchange -> {
            byte[] body = privateResponseData.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        try {
            ProviderStreamException failure = assertFailure(
                    provider(server, null, Map.of()).stream(request(), event -> { }),
                    ProviderStreamException.Kind.RESPONSE, privateResponseData);
            assertEquals(429, failure.httpStatus());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void abruptDisconnectIsTypedTransportFailureAndDoesNotHang() throws Exception {
        HttpServer server = server(exchange -> {
            startEventStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, deltaChunk("partial", null)); //$NON-NLS-1$
            } finally {
                exchange.close();
            }
        });
        try {
            List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> stream = provider(server, null, Map.of()).stream(request(), events::add);

            ProviderStreamException failure = assertFailure(stream,
                    ProviderStreamException.Kind.TRANSPORT, "partial"); //$NON-NLS-1$
            assertTrue(events.contains(new ProviderStreamEvent.TextDelta("partial"))); //$NON-NLS-1$
            assertTrue(events.stream().anyMatch(event -> event instanceof ProviderStreamEvent.Error error
                    && error.failure() == failure));
            assertFalse(events.stream().anyMatch(ProviderStreamEvent.Done.class::isInstance));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void cancellationCompletesQuicklyWhileLocalStreamIsOpen() throws Exception {
        CountDownLatch firstEventSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            startEventStream(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, deltaChunk("started", null)); //$NON-NLS-1$
                firstEventSent.countDown();
                try {
                    releaseServer.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                exchange.close();
            }
        });
        try {
            CountDownLatch received = new CountDownLatch(1);
            CompletableFuture<Void> stream = provider(server, null, Map.of()).stream(request(), event -> {
                if (event instanceof ProviderStreamEvent.TextDelta) received.countDown();
            });
            assertTrue(firstEventSent.await(1, TimeUnit.SECONDS));
            assertTrue(received.await(1, TimeUnit.SECONDS));

            assertTrue(stream.cancel(true));
            assertTrue(stream.isCancelled());
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    private static ProviderStreamException assertFailure(CompletableFuture<Void> future,
            ProviderStreamException.Kind expectedKind, String forbiddenDiagnostic) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            fail("Expected stream failure"); //$NON-NLS-1$
            throw new AssertionError();
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof ProviderStreamException);
            ProviderStreamException failure = (ProviderStreamException) expected.getCause();
            assertEquals(expectedKind, failure.kind());
            assertFalse(failure.toString().contains(forbiddenDiagnostic));
            return failure;
        } catch (TimeoutException timeout) {
            throw new AssertionError("Stream hung", timeout); //$NON-NLS-1$
        }
    }

    private static JsonObject request() {
        JsonObject request = new JsonObject();
        request.addProperty("model", "test-model"); //$NON-NLS-1$ //$NON-NLS-2$
        request.add("messages", new JsonArray()); //$NON-NLS-1$
        JsonObject options = new JsonObject();
        options.addProperty("custom_option", true); //$NON-NLS-1$
        request.add("stream_options", options); //$NON-NLS-1$
        return request;
    }

    private static String deltaChunk(String content, String reasoning) {
        JsonObject delta = new JsonObject();
        if (content != null) delta.addProperty("content", content); //$NON-NLS-1$
        if (reasoning != null) delta.addProperty("reasoning_content", reasoning); //$NON-NLS-1$
        return chunk(delta, null);
    }

    private static String toolChunk(int index, String id, String name, String arguments, String finishReason) {
        JsonObject function = new JsonObject();
        if (name != null) function.addProperty("name", name); //$NON-NLS-1$
        if (arguments != null) function.addProperty("arguments", arguments); //$NON-NLS-1$
        JsonObject call = new JsonObject();
        call.addProperty("index", index); //$NON-NLS-1$
        if (id != null) call.addProperty("id", id); //$NON-NLS-1$
        call.add("function", function); //$NON-NLS-1$
        JsonArray calls = new JsonArray();
        calls.add(call);
        JsonObject delta = new JsonObject();
        delta.add("tool_calls", calls); //$NON-NLS-1$
        return chunk(delta, finishReason);
    }

    private static String chunk(JsonObject delta, String finishReason) {
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0); //$NON-NLS-1$
        choice.add("delta", delta); //$NON-NLS-1$
        if (finishReason == null) choice.add("finish_reason", JsonNull.INSTANCE); //$NON-NLS-1$
        else choice.addProperty("finish_reason", finishReason); //$NON-NLS-1$
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices); //$NON-NLS-1$
        return root.toString();
    }

    private static String usageChunk(long input, long output, long total) {
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", input); //$NON-NLS-1$
        usage.addProperty("completion_tokens", output); //$NON-NLS-1$
        usage.addProperty("total_tokens", total); //$NON-NLS-1$
        JsonObject root = new JsonObject();
        root.add("choices", new JsonArray()); //$NON-NLS-1$
        root.add("usage", usage); //$NON-NLS-1$
        return root.toString();
    }

    private static OpenAiCompatibleProvider provider(HttpServer server, char[] apiKey,
            Map<String, String> headers) {
        ProviderConfiguration configuration = ProviderConfiguration.builder()
                .id("stream-test") //$NON-NLS-1$
                .displayName("Stream test") //$NON-NLS-1$
                .baseUri(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1")) //$NON-NLS-1$ //$NON-NLS-2$
                .defaultModel("test-model") //$NON-NLS-1$
                .headers(headers)
                .apiKey(apiKey)
                .build();
        return new RuntimeProviderFactory().create(configuration);
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); //$NON-NLS-1$
        server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange)); //$NON-NLS-1$
        server.start();
        return server;
    }

    private static void startEventStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(200, 0);
    }

    private static void writeEvent(OutputStream output, String json) throws IOException {
        output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        output.flush();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
