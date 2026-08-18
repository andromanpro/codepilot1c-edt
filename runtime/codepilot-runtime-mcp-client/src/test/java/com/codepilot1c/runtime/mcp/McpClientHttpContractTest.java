package com.codepilot1c.runtime.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class McpClientHttpContractTest {
    private HttpServer server;
    private McpClient client;
    private final List<String> protocols = new CopyOnWriteArrayList<>();
    private final List<String> sessions = new CopyOnWriteArrayList<>();
    private final AtomicInteger deletes = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @After
    public void tearDown() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test
    public void initializeNegotiatesFallbackAndPreservesSessionHeadersAndRawSchemas() {
        server.createContext("/health/ready", exchange -> write(exchange, 200,
                "{\"services\":\"ready\",\"projects\":[{\"name\":\"demo\",\"state\":\"ready\"}]}"));
        server.createContext("/mcp", exchange -> {
            String protocol = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            protocols.add(protocol);
            String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            sessions.add(session);
            String method = method(exchange);
            if ("initialize".equals(method) && "2025-11-25".equals(protocol)) {
                write(exchange, 400, "{\"error\":\"unsupported_protocol_version\",\"supported\":[\"2025-06-18\"]}");
                return;
            }
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                write(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                        + "\"protocolVersion\":\"2024-11-05\",\"serverInfo\":{\"name\":\"edt\",\"future\":true},"
                        + "\"capabilities\":{\"tools\":{}},\"experimental\":{\"codepilot\":{\"contractVersion\":1}}}}");
                return;
            }
            if ("tools/list".equals(method)) {
                write(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{"
                        + "\"name\":\"build\",\"description\":\"Build\",\"inputSchema\":{\"type\":\"object\",\"x-vendor\":{\"a\":1}}}]}}");
                return;
            }
            write(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}");
        });
        client = new McpClient(config("token-123"));

        HealthReadyResult readiness = client.healthReady().join();
        assertTrue(readiness.ready());
        InitializeResult initialized = client.initialize().join();
        assertEquals("2024-11-05", initialized.protocolVersion());
        assertEquals("edt", initialized.serverInfo().get("name").getAsString());
        assertEquals(1, initialized.experimentalCodepilot().get("contractVersion").getAsInt());
        ToolsListResult listed = client.listTools().join();
        assertEquals(1, listed.tools().size());
        assertEquals("{\"type\":\"object\",\"x-vendor\":{\"a\":1}}",
                listed.tools().get(0).inputSchema().toString());
        client.callTool("build", new JsonObject()).join();
        client.ping().join();

        assertEquals(List.of("2025-11-25", "2025-06-18", "2024-11-05", "2024-11-05", "2024-11-05"), protocols);
        assertNull(sessions.get(0));
        assertNull(sessions.get(1));
        assertEquals("session-1", sessions.get(2));
    }

    @Test
    public void initializeIsRequiredAndDoesNotCreateArbitrarySession() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/mcp", exchange -> { requests.incrementAndGet(); write(exchange, 200, "{}"); });
        client = new McpClient(config(null));
        try {
            client.listTools().join();
            fail("listTools should require initialize");
        } catch (java.util.concurrent.CompletionException e) {
            McpClientException error = (McpClientException) e.getCause();
            assertEquals(McpClientException.Kind.STATE, error.kind());
        }
        assertEquals(0, requests.get());
    }

    @Test
    public void jsonRpcErrorPreservesCodeAndDataAndDeleteUsesSession() {
        server.createContext("/mcp", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                assertEquals("session-1", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                deletes.incrementAndGet();
                write(exchange, 204, "");
                return;
            }
            if ("initialize".equals(method(exchange))) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                write(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
            } else {
                write(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32001,"
                        + "\"message\":\"denied\",\"data\":{\"reason\":\"policy\",\"unknown\":[1,2]}}}");
            }
        });
        client = new McpClient(config(null));
        client.initialize().join();
        try {
            client.ping().join();
            fail("ping should fail");
        } catch (java.util.concurrent.CompletionException e) {
            McpClientException error = (McpClientException) e.getCause();
            assertEquals(McpClientException.Kind.JSON_RPC, error.kind());
            assertEquals(-32001, error.rpcCode());
            assertEquals("policy", error.rpcData().getAsJsonObject().get("reason").getAsString());
        }
        client.close();
        assertEquals(1, deletes.get());
    }

    @Test
    public void non2xxAndMalformedBodiesAreTypedAndDoNotLeakBody() {
        server.createContext("/mcp", exchange -> {
            if ("initialize".equals(method(exchange))) write(exchange, 500, "secret-body");
        });
        client = new McpClient(config("super-secret"));
        try {
            client.initialize().join();
            fail("initialize should fail");
        } catch (java.util.concurrent.CompletionException e) {
            McpClientException error = (McpClientException) e.getCause();
            assertEquals(McpClientException.Kind.HTTP, error.kind());
            assertEquals(500, error.httpStatus());
            assertFalse(error.toString().contains("secret-body"));
            assertFalse(error.toString().contains("super-secret"));
        }
    }

    @Test
    public void configRejectsUnsafeEndpointsAndRedactsCredentials() {
        try { McpClientConfig.builder("ftp://127.0.0.1/mcp").build(); fail(); }
        catch (IllegalArgumentException expected) { }
        try { McpClientConfig.builder("http://example.com/mcp").build(); fail(); }
        catch (IllegalArgumentException expected) { }
        try { McpClientConfig.builder("https://user:pass@example.com/mcp").build(); fail(); }
        catch (IllegalArgumentException expected) { }
        try { McpClientConfig.builder("http://evil-name/mcp").build(); fail(); }
        catch (IllegalArgumentException expected) { }
        try {
            McpClientConfig.builder("https://example.com/mcp")
                    .protocolPreferences(List.of("2099-01-01")).build();
            fail();
        } catch (IllegalArgumentException expected) { }
        McpClientConfig config = config("super-secret");
        assertFalse(config.toString().contains("super-secret"));
        assertTrue(config.toString().contains("<redacted>"));
        config.close();
        assertNull(config.copyBearerToken());
    }

    @Test
    public void productionSourceHasNoPlatformImportsAndPomHasExactRuntimeDependencies() throws IOException {
        Path source = Path.of(System.getProperty("mcp.client.basedir", "."), "src/main/java");
        if (!Files.isDirectory(source)) return;
        try (var files = Files.walk(source)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    assertFalse(text, text.contains("org.eclipse."));
                    assertFalse(text, text.contains("org.osgi."));
                    assertFalse(text, text.contains("org.eclipse.swt"));
                    assertFalse(text, text.contains("com.codepilot1c.core"));
                } catch (IOException e) { throw new AssertionError(e); }
            });
        }
        String pom = Files.readString(source.getParent().getParent().getParent().resolve("pom.xml"));
        assertTrue(pom.contains("com.google.code.gson"));
        assertFalse(pom.contains("codepilot-runtime-provider"));
    }

    private McpClientConfig config(String token) {
        return McpClientConfig.builder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"))
                .bearerToken(token == null ? null : token.toCharArray())
                .requestTimeout(Duration.ofSeconds(5)).build();
    }

    private static String method(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return "";
        try { return com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("method").getAsString(); }
        catch (RuntimeException e) { return ""; }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
