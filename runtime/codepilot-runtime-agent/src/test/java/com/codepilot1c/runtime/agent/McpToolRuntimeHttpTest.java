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
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.runtime.mcp.McpClient;
import com.codepilot1c.runtime.mcp.McpClientConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class McpToolRuntimeHttpTest {

    @Test
    public void mapsAnnotatedAndLegacyToolContracts() throws Exception {
        HttpServer server = toolServer((exchange, request, method, id) -> {
            if ("tools/list".equals(method)) { //$NON-NLS-1$
                respond(exchange, 200, rpc(id, """
                        {"tools":[
                          {"name":"write","description":"Writes","inputSchema":{"type":"object"},
                           "annotations":{"title":"Write file","destructiveHint":true,"readOnlyHint":false},
                           "_meta":{"codepilot1c/requiresConfirmation":true}},
                          {"name":"inspect","description":"Inspects","inputSchema":{"type":"object"},
                           "annotations":{"readOnlyHint":true}},
                          {"name":"legacy","description":"Legacy","inputSchema":{"type":"object"}}
                        ]}
                        """), false);
            } else {
                respond(exchange, 200, rpc(id, "{}"), false); //$NON-NLS-1$
            }
        });
        McpClient client = client(server);
        try {
            McpToolRuntime runtime = McpToolRuntime.connect(client).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            ToolAnnotations annotations = runtime.tools().get(0).annotations().orElseThrow();
            assertEquals("Write file", annotations.title()); //$NON-NLS-1$
            assertTrue(annotations.destructive());
            assertFalse(annotations.readOnly());
            assertTrue(annotations.requiresConfirmation());
            ToolAnnotations readOnly = runtime.tools().get(1).annotations().orElseThrow();
            assertEquals("", readOnly.title()); //$NON-NLS-1$
            assertFalse(readOnly.destructive());
            assertTrue(readOnly.readOnly());
            assertFalse(readOnly.requiresConfirmation());
            assertTrue(runtime.tools().get(2).annotations().isEmpty());
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    public void refreshPublishesOnlyCompleteCatalogAndExecutionUsesVisibleSnapshot() throws Exception {
        AtomicInteger lists = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        ExecutorService executor = Executors.newCachedThreadPool();
        HttpServer server = toolServer((exchange, request, method, id) -> {
            if ("tools/list".equals(method)) { //$NON-NLS-1$
                int list = lists.incrementAndGet();
                if (list == 1) {
                    respond(exchange, 200, rpc(id, tools("old")), false); //$NON-NLS-1$
                } else if (list == 2) {
                    refreshStarted.countDown();
                    await(releaseRefresh);
                    respond(exchange, 200, rpc(id, tools("new")), false); //$NON-NLS-1$
                } else {
                    respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id //$NON-NLS-1$
                            + ",\"error\":{\"code\":-32000,\"message\":\"refresh failed\"}}", false); //$NON-NLS-1$
                }
            } else if ("tools/call".equals(method)) { //$NON-NLS-1$
                calls.incrementAndGet();
                respond(exchange, 200, rpc(id, "{\"isError\":false}"), false); //$NON-NLS-1$
            } else {
                respond(exchange, 200, rpc(id, "{}"), false); //$NON-NLS-1$
            }
        }, executor);
        McpClient client = client(server);
        try {
            McpToolRuntime runtime = McpToolRuntime.connect(client).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            CompletableFuture<List<ToolDefinition>> refresh = runtime.refresh().toCompletableFuture();
            assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));
            assertEquals("old", runtime.tools().get(0).name()); //$NON-NLS-1$

            ToolExecutionResult duringRefresh = runtime.execute(
                    "old", new JsonObject(), CancellationToken.none()).toCompletableFuture() //$NON-NLS-1$
                    .get(2, TimeUnit.SECONDS);
            assertFalse(duringRefresh.error());
            assertEquals(1, calls.get());
            assertEquals("old", runtime.tools().get(0).name()); //$NON-NLS-1$

            releaseRefresh.countDown();
            assertEquals("new", refresh.get(2, TimeUnit.SECONDS).get(0).name()); //$NON-NLS-1$
            assertEquals("new", runtime.tools().get(0).name()); //$NON-NLS-1$

            try {
                runtime.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS);
                fail("failed refresh should be exceptional"); //$NON-NLS-1$
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() != null);
            }
            assertEquals("new", runtime.tools().get(0).name()); //$NON-NLS-1$
        } finally {
            releaseRefresh.countDown();
            client.close();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    public void cancelledRefreshCannotReplaceCatalogWithLateResponse() throws Exception {
        RefreshRace race = new RefreshRace();
        try {
            McpToolRuntime runtime = race.connect();
            CompletableFuture<List<ToolDefinition>> refresh = runtime.refresh().toCompletableFuture();
            assertTrue(race.refreshStarted.await(2, TimeUnit.SECONDS));
            assertTrue(refresh.cancel(true));
            assertTrue(refresh.isCancelled());
            race.releaseRefresh.countDown();
            assertTrue(race.refreshFinished.await(2, TimeUnit.SECONDS));
            assertEquals("old", runtime.tools().get(0).name()); //$NON-NLS-1$
        } finally {
            race.close();
        }
    }

    @Test
    public void closeDuringRefreshRejectsLateCatalogAndRetainsSnapshot() throws Exception {
        RefreshRace race = new RefreshRace();
        try {
            McpToolRuntime runtime = race.connect();
            CompletableFuture<List<ToolDefinition>> refresh = runtime.refresh().toCompletableFuture();
            assertTrue(race.refreshStarted.await(2, TimeUnit.SECONDS));
            race.client.closeAsync().get(2, TimeUnit.SECONDS);
            race.releaseRefresh.countDown();
            try {
                refresh.get(2, TimeUnit.SECONDS);
                fail("refresh should fail after close"); //$NON-NLS-1$
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() != null);
            } catch (CancellationException expected) {
                // An HTTP implementation may cancel the in-flight exchange on close.
            }
            assertEquals("old", runtime.tools().get(0).name()); //$NON-NLS-1$
        } finally {
            race.close();
        }
    }

    @Test
    public void initializesListsAndExecutesThroughMcpBoundary() throws Exception {
        AtomicReference<JsonObject> called = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) { //$NON-NLS-1$
                respond(exchange, 204, "", false); //$NON-NLS-1$
                return;
            }
            JsonObject request = JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String method = request.get("method").getAsString(); //$NON-NLS-1$
            long id = request.get("id").getAsLong(); //$NON-NLS-1$
            switch (method) {
                case "initialize": //$NON-NLS-1$
                    String protocol = request.getAsJsonObject("params") //$NON-NLS-1$
                            .get("protocolVersion").getAsString(); //$NON-NLS-1$
                    respond(exchange, 200, rpc(id,
                            "{\"protocolVersion\":\"" + protocol //$NON-NLS-1$
                            + "\",\"serverInfo\":{},\"capabilities\":{}}"), true); //$NON-NLS-1$
                    break;
                case "tools/list": //$NON-NLS-1$
                    respond(exchange, 200, rpc(id, """
                            {"tools":[{"name":"sum","description":"Adds values","inputSchema":{
                              "type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},
                              "required":["a","b"]}}]}
                            """), false);
                    break;
                case "tools/call": //$NON-NLS-1$
                    called.set(request.getAsJsonObject("params")); //$NON-NLS-1$
                    respond(exchange, 200, rpc(id,
                            "{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"3\"}]}"), //$NON-NLS-1$
                            false);
                    break;
                default:
                    respond(exchange, 500, "{}", false); //$NON-NLS-1$
                    break;
            }
        });
        McpClientConfig config = McpClientConfig.builder(URI.create(
                "http://localhost:" + server.getAddress().getPort() + "/mcp")) //$NON-NLS-1$ //$NON-NLS-2$
                .requestTimeout(Duration.ofSeconds(2)).build();
        try (McpClient client = new McpClient(config)) {
            McpToolRuntime runtime = McpToolRuntime.connect(client).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(1, runtime.tools().size());
            assertEquals("sum", runtime.tools().get(0).name()); //$NON-NLS-1$
            assertEquals("object", runtime.tools().get(0).inputSchema().get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

            JsonObject arguments = JsonParser.parseString("{\"a\":1,\"b\":2}").getAsJsonObject(); //$NON-NLS-1$
            ToolExecutionResult result = runtime.execute("sum", arguments, CancellationToken.none()) //$NON-NLS-1$
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertFalse(result.error());
            assertEquals("sum", called.get().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(2, called.get().getAsJsonObject("arguments").get("b").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(result.data().getAsJsonObject().has("content")); //$NON-NLS-1$
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void closesMcpSessionWhenToolListingFails() throws Exception {
        AtomicInteger deletes = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) { //$NON-NLS-1$
                deletes.incrementAndGet();
                respond(exchange, 204, "", false); //$NON-NLS-1$
                return;
            }
            JsonObject request = JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String method = request.get("method").getAsString(); //$NON-NLS-1$
            long id = request.get("id").getAsLong(); //$NON-NLS-1$
            if ("initialize".equals(method)) { //$NON-NLS-1$
                String protocol = request.getAsJsonObject("params") //$NON-NLS-1$
                        .get("protocolVersion").getAsString(); //$NON-NLS-1$
                respond(exchange, 200, rpc(id,
                        "{\"protocolVersion\":\"" + protocol //$NON-NLS-1$
                        + "\",\"serverInfo\":{},\"capabilities\":{}}"), true); //$NON-NLS-1$
            } else {
                respond(exchange, 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":" + id //$NON-NLS-1$
                        + ",\"error\":{\"code\":-32000,\"message\":\"listing failed\"}}", false); //$NON-NLS-1$
            }
        });
        McpClientConfig config = McpClientConfig.builder(URI.create(
                "http://localhost:" + server.getAddress().getPort() + "/mcp")) //$NON-NLS-1$ //$NON-NLS-2$
                .requestTimeout(Duration.ofSeconds(2)).build();
        try (McpClient client = new McpClient(config)) {
            try {
                McpToolRuntime.connect(client).toCompletableFuture().get(2, TimeUnit.SECONDS);
                fail("connect should fail when tools/list fails"); //$NON-NLS-1$
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() != null);
            }
            assertEquals(1, deletes.get());
            assertFalse(client.isInitialized());
        } finally {
            server.stop(0);
        }
    }

    private static String rpc(long id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); //$NON-NLS-1$
        server.createContext("/mcp", handler::handle); //$NON-NLS-1$
        server.start();
        return server;
    }

    private static HttpServer toolServer(ToolExchangeHandler handler) throws IOException {
        return toolServer(handler, null);
    }

    private static HttpServer toolServer(ToolExchangeHandler handler, ExecutorService executor)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); //$NON-NLS-1$
        if (executor != null) server.setExecutor(executor);
        server.createContext("/mcp", exchange -> { //$NON-NLS-1$
            if ("DELETE".equals(exchange.getRequestMethod())) { //$NON-NLS-1$
                respond(exchange, 204, "", false); //$NON-NLS-1$
                return;
            }
            JsonObject request = JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String method = request.get("method").getAsString(); //$NON-NLS-1$
            long id = request.get("id").getAsLong(); //$NON-NLS-1$
            if ("initialize".equals(method)) { //$NON-NLS-1$
                String protocol = request.getAsJsonObject("params") //$NON-NLS-1$
                        .get("protocolVersion").getAsString(); //$NON-NLS-1$
                respond(exchange, 200, rpc(id, "{\"protocolVersion\":\"" + protocol //$NON-NLS-1$
                        + "\",\"serverInfo\":{},\"capabilities\":{}}"), true); //$NON-NLS-1$
                return;
            }
            handler.handle(exchange, request, method, id);
        });
        server.start();
        return server;
    }

    private static McpClient client(HttpServer server) {
        return new McpClient(McpClientConfig.builder(URI.create(
                "http://localhost:" + server.getAddress().getPort() + "/mcp")) //$NON-NLS-1$ //$NON-NLS-2$
                .requestTimeout(Duration.ofSeconds(2)).build());
    }

    private static String tools(String name) {
        return "{\"tools\":[{\"name\":\"" + name //$NON-NLS-1$
                + "\",\"description\":\"Tool\",\"inputSchema\":{\"type\":\"object\"}}]}"; //$NON-NLS-1$
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IOException("timed out waiting for test latch"); //$NON-NLS-1$
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("test server interrupted", interrupted); //$NON-NLS-1$
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, boolean initialize)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (initialize) exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(status, bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    private interface ToolExchangeHandler {
        void handle(HttpExchange exchange, JsonObject request, String method, long id) throws IOException;
    }

    private static final class RefreshRace implements AutoCloseable {
        private final AtomicInteger lists = new AtomicInteger();
        private final CountDownLatch refreshStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRefresh = new CountDownLatch(1);
        private final CountDownLatch refreshFinished = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final HttpServer server;
        private final McpClient client;

        RefreshRace() throws IOException {
            server = toolServer((exchange, request, method, id) -> {
                if (!"tools/list".equals(method)) { //$NON-NLS-1$
                    respond(exchange, 200, rpc(id, "{}"), false); //$NON-NLS-1$
                    return;
                }
                if (lists.incrementAndGet() == 1) {
                    respond(exchange, 200, rpc(id, tools("old")), false); //$NON-NLS-1$
                    return;
                }
                refreshStarted.countDown();
                await(releaseRefresh);
                try {
                    respond(exchange, 200, rpc(id, tools("late")), false); //$NON-NLS-1$
                } finally {
                    refreshFinished.countDown();
                }
            }, executor);
            client = client(server);
        }

        McpToolRuntime connect() throws Exception {
            return McpToolRuntime.connect(client).toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            releaseRefresh.countDown();
            client.close();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
