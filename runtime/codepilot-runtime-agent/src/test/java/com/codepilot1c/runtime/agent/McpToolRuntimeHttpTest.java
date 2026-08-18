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
import java.util.concurrent.TimeUnit;
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

    private static String rpc(long id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); //$NON-NLS-1$
        server.createContext("/mcp", handler::handle); //$NON-NLS-1$
        server.start();
        return server;
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
}
