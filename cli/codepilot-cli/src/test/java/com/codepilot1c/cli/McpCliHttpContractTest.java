/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.supervisor.DefaultSupervisorFileSystem;
import com.codepilot1c.cli.supervisor.InstanceRecord;
import com.codepilot1c.cli.supervisor.InstanceRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** CLI-level contract tests: the command must use the standalone client, not duplicate MCP transport. */
public class McpCliHttpContractTest {
    private HttpServer server;
    private String endpoint;
    private final AtomicInteger initializeCalls = new AtomicInteger();
    private final AtomicBoolean deleteSeen = new AtomicBoolean();
    private final AtomicBoolean authRequired = new AtomicBoolean();

    @Before public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health/ready", this::health);
        server.createContext("/mcp", this::mcp);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void stopServer() { if (server != null) server.stop(0); }

    @Test public void toolsNegotiatesProtocolInitializesAndClosesOneShortLivedSession() {
        Fixture fixture = new Fixture("");
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "mcp", "tools", "--endpoint", endpoint));
        assertEquals(2, initializeCalls.get()); // first protocol is explicitly rejected, second is accepted
        assertTrue(deleteSeen.get());
        assertTrue(fixture.out().contains("\"protocolVersion\":\"2025-06-18\""));
        assertTrue(fixture.out().contains("\"name\":\"echo\""));
    }

    @Test public void healthUsesTheReadinessEndpointWithoutCreatingSession() {
        Fixture fixture = new Fixture("");
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "mcp", "health", "--endpoint", endpoint));
        assertEquals(0, initializeCalls.get());
        assertTrue(fixture.out().contains("\"ready\":true"));
        assertTrue(fixture.out().contains("\"body\":{\"ready\":true}"));
    }

    @Test public void initializeAndCloseAreBothShortLivedSessionLifecycleChecks() {
        Fixture fixture = new Fixture("");
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "mcp", "initialize", "--endpoint", endpoint));
        assertTrue(deleteSeen.get());
        assertTrue(fixture.out().contains("\"status\":\"initialized\""));

        deleteSeen.set(false);
        initializeCalls.set(0);
        fixture.reset("");
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "mcp", "close", "--endpoint", endpoint));
        assertTrue(deleteSeen.get());
        assertTrue(fixture.out().contains("\"status\":\"closed\""));
    }

    @Test public void callAcceptsOneObjectFromStdinAndRedactsSensitiveServerFields() {
        Fixture fixture = new Fixture("{\"message\":\"hello\"}");
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "mcp", "call", "echo",
                "--args-stdin", "--endpoint", endpoint));
        assertTrue(fixture.out().contains("\"tool\":\"echo\""));
        assertFalse(fixture.out().toLowerCase(java.util.Locale.ROOT).contains("authorization"));
        assertFalse(fixture.out().contains("should-not-appear"));
    }

    @Test public void argumentSourcesAreMutuallyExclusiveAndMustBeObjects() {
        Fixture fixture = new Fixture("");
        assertEquals(ExitCodes.USAGE, fixture.execute("mcp", "call", "echo", "--args", "{}",
                "--args-stdin", "--endpoint", endpoint));
        assertEquals(0, initializeCalls.get());

        fixture.reset("[]");
        assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "mcp", "call", "echo",
                "--args-stdin", "--endpoint", endpoint));
        assertTrue(fixture.out().contains("\"error\":\"arguments_must_be_object\""));
    }

    @Test public void authFailureUsesExitThreeWithoutLeakingCredential() {
        authRequired.set(true);
        Fixture fixture = new Fixture("");
        fixture.host.environment.put("CODEPILOT_MCP_BEARER_TOKEN", "top-secret-token");
        assertEquals(ExitCodes.AUTH, fixture.execute("--output", "json", "mcp", "ping", "--endpoint", endpoint));
        assertTrue(fixture.out().contains("\"error\":\"authentication_failed\""));
        assertFalse(fixture.out().contains("top-secret-token"));
        assertFalse(fixture.err().contains("top-secret-token"));
    }

    @Test public void instanceIdSelectsOnlyValidatedRegistryRecord() throws IOException {
        Fixture fixture = new Fixture("");
        Path home = Files.createTempDirectory("codepilot mcp cli ");
        fixture.host.home = home.toString();
        String id = UUID.randomUUID().toString();
        InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(),
                home.resolve(".codepilot1c").resolve("instances"));
        registry.write(new InstanceRecord(InstanceRecord.SCHEMA_VERSION, id, 123L,
                server.getAddress().getPort(), endpoint, "/workspace", "/edt", "headless", "cli",
                Instant.now(), null, null, null));
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "mcp", "ping", "--instance-id", id));
        assertTrue(fixture.out().contains("\"endpoint\":\"" + endpoint + "/mcp\""));
    }

    private void health(HttpExchange exchange) throws IOException {
        if (authRequired.get()) { send(exchange, 401, "{\"error\":\"unauthorized\"}"); return; }
        send(exchange, 200, "{\"ready\":true}");
    }

    private void mcp(HttpExchange exchange) throws IOException {
        if (authRequired.get()) {
            assertEquals("Bearer top-secret-token", exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 401, "{\"error\":{\"code\":-32001,\"message\":\"unauthorized\"}}");
            return;
        }
        if ("DELETE".equals(exchange.getRequestMethod())) { deleteSeen.set(true); send(exchange, 204, ""); return; }
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = com.google.gson.JsonParser.parseString(request).getAsJsonObject().get("method").getAsString();
        if ("initialize".equals(method)) {
            int call = initializeCalls.incrementAndGet();
            if (call == 1) { send(exchange, 400, "{\"error\":\"Unsupported protocol version\"}"); return; }
            exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"test\"},\"capabilities\":{}}}");
            return;
        }
        assertEquals("session-1", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
        if ("tools/list".equals(method)) {
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}}");
        } else if ("tools/call".equals(method)) {
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"authorization\":\"Bearer should-not-appear\",\"echo\":\"Bearer should-not-appear\"}}");
        } else if ("ping".equals(method)) {
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}");
        } else throw new AssertionError("unexpected MCP method: " + method);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        else exchange.close();
    }

    private static final class Fixture {
        final FakeHostSystem host = new FakeHostSystem();
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        String stdin;
        Fixture(String stdin) { this.stdin = stdin; }
        void reset(String input) { output = new StringWriter(); errors = new StringWriter(); stdin = input; }
        int execute(String... args) {
            CliServices services = new CliServices(host, new EdtInstallationDiscovery(host), new CliConfiguration(host),
                    endpoint -> new EndpointProbe.ProbeResult(true, 200, "HTTP 200"), new PrintWriter(output, true),
                    new PrintWriter(errors, true), new StringReader(stdin), "9.8.7");
            return CodePilotCli.execute(services, args);
        }
        String out() { return output.toString(); }
        String err() { return errors.toString(); }
    }
}
