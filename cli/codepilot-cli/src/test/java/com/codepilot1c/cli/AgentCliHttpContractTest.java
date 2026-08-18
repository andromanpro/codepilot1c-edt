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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** End-to-end CLI agent contract across independent provider and MCP HTTP servers. */
public class AgentCliHttpContractTest {
    private enum ProviderMode { TOOL_THEN_TEXT, FINAL_TEXT, ECHO_AUTH_TEXT, AUTH, MALFORMED, SLOW, ALWAYS_TOOL }
    private enum McpMode { NORMAL, AUTH }

    private HttpServer providerServer;
    private HttpServer mcpServer;
    private String providerEndpoint;
    private String mcpEndpoint;
    private volatile ProviderMode providerMode = ProviderMode.TOOL_THEN_TEXT;
    private volatile McpMode mcpMode = McpMode.NORMAL;
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicBoolean mcpClosed = new AtomicBoolean();
    private final AtomicReference<String> providerAuthorization = new AtomicReference<>();
    private final AtomicReference<String> providerModel = new AtomicReference<>();
    private final AtomicReference<String> mcpAuthorization = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> providerRequestStarted =
            new AtomicReference<>(new CountDownLatch(1));

    @Before public void startServers() throws IOException {
        providerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerServer.createContext("/v1/chat/completions", this::provider);
        providerServer.setExecutor(command -> {
            Thread thread = new Thread(command, "agent-provider-test");
            thread.setDaemon(true);
            thread.start();
        });
        providerServer.start();
        providerEndpoint = "http://127.0.0.1:" + providerServer.getAddress().getPort() + "/v1";

        mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.createContext("/mcp", this::mcp);
        mcpServer.start();
        mcpEndpoint = "http://127.0.0.1:" + mcpServer.getAddress().getPort();
    }

    @After public void stopServers() {
        if (providerServer != null) providerServer.stop(0);
        if (mcpServer != null) mcpServer.stop(0);
    }

    @Test public void modelToolMcpAndFinalTextCompleteOneShortLivedSession() {
        Fixture fixture = configured("");
        int exit = fixture.execute("--output", "json", "agent", "run", "--prompt", "do not echo",
                "--provider-endpoint", providerEndpoint, "--model", "test-model",
                "--provider-api-key-file", fixture.providerTokenFile().toString(), "--mcp-endpoint", mcpEndpoint,
                "--mcp-bearer-token-file", fixture.mcpTokenFile().toString());

        assertEquals(ExitCodes.OK, exit);
        assertEquals("{\"command\":\"agent run\",\"status\":\"completed\",\"terminalReason\":\"completed\",\"steps\":2,\"text\":\"prefix-<redacted>-mid-<redacted>-suffix password=hunter2\"}\n",
                fixture.out());
        assertEquals(2, providerCalls.get());
        assertEquals(1, toolCalls.get());
        assertTrue(mcpClosed.get());
        assertEquals("Bearer provider-secret", providerAuthorization.get());
        assertEquals("Bearer mcp-secret", mcpAuthorization.get());
        assertFalse(fixture.out().contains("provider-secret"));
        assertFalse(fixture.out().contains("mcp-secret"));
        assertTrue(fixture.out().contains("password=hunter2"));
    }

    @Test public void explicitProviderFlagsOverridePropertyAndEnvironment() {
        providerMode = ProviderMode.FINAL_TEXT;
        Fixture fixture = configured("");
        fixture.host.properties.put("codepilot.provider.endpoint", "http://127.0.0.1:1/v1");
        fixture.host.properties.put("codepilot.provider.model", "property-model");
        fixture.host.properties.put("codepilot.provider.apiKey", "property-secret");
        fixture.host.environment.put("CODEPILOT_PROVIDER_API_KEY", "environment-secret");

        assertEquals(ExitCodes.OK, fixture.execute("agent", "run", "--prompt", "hello", "--no-tools",
                "--provider-endpoint", providerEndpoint, "--model", "flag-model",
                "--provider-api-key-file", fixture.providerTokenFile().toString()));
        assertEquals("Bearer provider-secret", providerAuthorization.get());
        assertEquals("flag-model", providerModel.get());
        assertFalse(fixture.out().contains("property-secret"));
        assertFalse(fixture.out().contains("environment-secret"));

        fixture.reset("");
        fixture.host.properties.put("codepilot.provider.endpoint", providerEndpoint);
        fixture.host.environment.put("CODEPILOT_PROVIDER_ENDPOINT", "http://127.0.0.1:2/v1");
        fixture.host.properties.put("codepilot.provider.model", "property-model");
        fixture.host.environment.put("CODEPILOT_PROVIDER_MODEL", "environment-model");
        assertEquals(ExitCodes.OK, fixture.execute("agent", "run", "--prompt", "hello", "--no-tools"));
        assertEquals("Bearer property-secret", providerAuthorization.get());
        assertEquals("property-model", providerModel.get());
    }

    @Test public void textOutputRedactsEmbeddedConfiguredProviderSecretOnly() {
        providerMode = ProviderMode.ECHO_AUTH_TEXT;
        Fixture fixture = configured("");
        assertEquals(ExitCodes.OK, fixture.execute("agent", "run", "--prompt", "hello", "--no-tools",
                "--provider-endpoint", providerEndpoint, "--model", "test-model",
                "--provider-api-key-file", fixture.providerTokenFile().toString()));
        assertEquals("status=completed reason=completed steps=1\nbefore <redacted> after password=hunter2\n",
                fixture.out());
    }

    @Test public void providerAndMcpAuthenticationFailuresUseExitThree() {
        providerMode = ProviderMode.AUTH;
        Fixture fixture = configured("");
        assertEquals(ExitCodes.AUTH, fixture.execute("--output", "json", "agent", "run",
                "--prompt", "hello", "--no-tools", "--provider-endpoint", providerEndpoint,
                "--model", "test-model", "--provider-api-key-file", fixture.providerTokenFile().toString()));
        assertTrue(fixture.out().contains("\"terminalReason\":\"provider_auth\""));
        assertFalse(fixture.out().contains("provider-secret"));

        providerMode = ProviderMode.FINAL_TEXT;
        mcpMode = McpMode.AUTH;
        fixture.reset("");
        assertEquals(ExitCodes.AUTH, fixture.execute("--output", "json", "agent", "run",
                "--prompt", "hello", "--provider-endpoint", providerEndpoint, "--model", "test-model",
                "--mcp-endpoint", mcpEndpoint));
        assertTrue(fixture.out().contains("\"terminalReason\":\"authentication_failed\""));
        assertEquals(0, providerCalls.get() - 1); // MCP setup fails before a new provider request
    }

    @Test public void malformedProviderResponseAndStepLimitAreTypedTerminalResults() {
        providerMode = ProviderMode.MALFORMED;
        Fixture fixture = configured("");
        assertEquals(ExitCodes.FAILURE, fixture.execute("--output", "json", "agent", "run",
                "--prompt", "hello", "--no-tools", "--provider-endpoint", providerEndpoint,
                "--model", "test-model"));
        assertTrue(fixture.out().contains("\"terminalReason\":\"provider_response\""));
        assertTrue(fixture.out().contains("\"steps\":1"));

        providerMode = ProviderMode.ALWAYS_TOOL;
        fixture.reset("");
        assertEquals(ExitCodes.FAILURE, fixture.execute("--output", "json", "agent", "run",
                "--prompt", "hello", "--provider-endpoint", providerEndpoint, "--model", "test-model",
                "--mcp-endpoint", mcpEndpoint, "--max-steps", "1"));
        assertTrue(fixture.out().contains("\"status\":\"step_limit\""));
        assertTrue(fixture.out().contains("\"terminalReason\":\"step_limit\""));
        assertTrue(fixture.out().contains("\"steps\":1"));
        assertEquals(1, toolCalls.get());
    }

    @Test public void globalTimeoutAndThreadInterruptionCancelInFlightProvider() throws Exception {
        providerMode = ProviderMode.SLOW;
        Fixture fixture = configured("");
        assertEquals(ExitCodes.FAILURE, fixture.execute("--output", "json", "agent", "run",
                "--prompt", "hello", "--no-tools", "--provider-endpoint", providerEndpoint,
                "--model", "test-model", "--timeout", "1"));
        assertTrue(fixture.out().contains("\"status\":\"timed_out\""));

        fixture.reset("");
        CountDownLatch secondRequest = new CountDownLatch(1);
        providerRequestStarted.set(secondRequest);
        AtomicInteger exit = new AtomicInteger(-1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread command = new Thread(() -> {
            exit.set(fixture.execute("--output", "json", "agent", "run", "--prompt", "hello",
                    "--no-tools", "--provider-endpoint", providerEndpoint, "--model", "test-model"));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        command.start();
        assertTrue(secondRequest.await(2, TimeUnit.SECONDS));
        command.interrupt();
        command.join(3000);
        assertFalse(command.isAlive());
        assertEquals(ExitCodes.FAILURE, exit.get());
        assertTrue(fixture.out().contains("\"status\":\"cancelled\""));
        assertTrue(interruptRestored.get());
    }

    @Test public void promptSourcesAreExclusiveAndNoToolsRejectsMcpOptions() throws IOException {
        Fixture fixture = configured("stdin prompt");
        Path prompt = Files.createTempFile("agent prompt ", ".txt");
        Files.writeString(prompt, "file prompt", StandardCharsets.UTF_8);
        try {
            assertEquals(ExitCodes.USAGE, fixture.execute("agent", "run", "--prompt-file",
                    prompt.toString(), "--prompt-stdin", "--no-tools", "--provider-endpoint",
                    providerEndpoint, "--model", "test-model"));
            assertTrue(fixture.err().startsWith("error[usage]:"));

            fixture.reset("");
            assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "agent", "run",
                    "--prompt", "hello", "--no-tools", "--mcp-endpoint", mcpEndpoint,
                    "--provider-endpoint", providerEndpoint, "--model", "test-model"));
            assertTrue(fixture.out().contains("no_tools_mcp_options_conflict"));
            assertEquals(0, providerCalls.get());
        } finally {
            Files.deleteIfExists(prompt);
        }
    }

    @Test public void providerEndpointAndSecretFileSafetyFailBeforeNetworkUse() throws IOException {
        Fixture fixture = configured("");
        assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "agent", "run",
                "--prompt", "hello", "--no-tools", "--provider-endpoint", "http://example.com/v1",
                "--model", "test-model"));
        assertTrue(fixture.out().contains("insecure_provider_endpoint"));

        fixture.reset("");
        assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "agent", "run",
                "--prompt", "hello", "--no-tools",
                "--provider-endpoint", "http://secret-user@example.com/v1", "--model", "test-model"));
        assertTrue(fixture.out().contains("invalid_provider_configuration"));
        assertFalse(fixture.out().contains("secret-user"));

        fixture.reset("");
        assertEquals(ExitCodes.OK, fixture.execute("agent", "run", "--help"));
        assertFalse(fixture.out().contains("--provider-api-key "));

        Path broad = Files.createTempFile("agent broad secret ", ".txt");
        Files.writeString(broad, "do-not-use", StandardCharsets.UTF_8);
        try {
            try {
                Files.setPosixFilePermissions(broad, PosixFilePermissions.fromString("rw-r--r--"));
                fixture.reset("");
                assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "agent", "run",
                        "--prompt", "hello", "--no-tools", "--provider-endpoint", providerEndpoint,
                        "--model", "test-model", "--provider-api-key-file", broad.toString()));
                assertTrue(fixture.out().contains("provider_api_key_file_unreadable"));
                assertFalse(fixture.out().contains("do-not-use"));
            } catch (UnsupportedOperationException ignored) {
                // ACL-based platform: the production path still applies no-follow and regular-file checks.
            }
        } finally {
            Files.deleteIfExists(broad);
        }
        assertEquals(0, providerCalls.get());
    }

    private Fixture configured(String stdin) {
        Fixture fixture = new Fixture(stdin);
        fixture.host.environment.put("CODEPILOT_MCP_BEARER_TOKEN", "mcp-secret");
        return fixture;
    }

    private void provider(HttpExchange exchange) throws IOException {
        providerRequestStarted.get().countDown();
        providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        JsonObject request = JsonParser.parseString(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(request.has("model"));
        providerModel.set(request.get("model").getAsString());
        int call = providerCalls.incrementAndGet();
        switch (providerMode) {
        case AUTH -> send(exchange, 401, "{\"error\":{\"message\":\"unauthorized\"}}");
        case MALFORMED -> send(exchange, 200, "{}");
        case SLOW -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException interruption) { Thread.currentThread().interrupt(); }
            sendIgnoringDisconnect(exchange, 200, assistantText("late"));
        }
        case FINAL_TEXT -> send(exchange, 200, assistantText("final"));
        case ECHO_AUTH_TEXT -> send(exchange, 200, assistantText("before "
                + providerAuthorization.get().substring("Bearer ".length())
                + " after password=hunter2"));
        case ALWAYS_TOOL -> send(exchange, 200, assistantTool("call-" + call));
        case TOOL_THEN_TEXT -> {
            if (call == 1) {
                assertTrue(request.has("tools"));
                send(exchange, 200, assistantTool("call-1"));
            } else {
                String serialized = request.toString();
                assertTrue(serialized.contains("tool_call_id"));
                assertTrue(serialized.contains("Tool completed"));
                send(exchange, 200, assistantText(
                        "prefix-provider-secret-mid-mcp-secret-suffix password=hunter2"));
            }
        }
        }
    }

    private void mcp(HttpExchange exchange) throws IOException {
        mcpAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        if (mcpMode == McpMode.AUTH) {
            send(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        if ("DELETE".equals(exchange.getRequestMethod())) {
            mcpClosed.set(true);
            send(exchange, 204, "");
            return;
        }
        JsonObject request = JsonParser.parseString(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        String id = request.get("id").toString();
        String method = request.get("method").getAsString();
        if ("initialize".equals(method)) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", "agent-session");
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"protocolVersion\":\"2025-11-25\",\"serverInfo\":{\"name\":\"test\"},\"capabilities\":{}}}");
        } else if ("tools/list".equals(method)) {
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}}]}}");
        } else if ("tools/call".equals(method)) {
            toolCalls.incrementAndGet();
            send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"echoed\"}],\"apiKey\":\"tool-secret\"}}");
        } else {
            throw new AssertionError("unexpected method: " + method);
        }
    }

    private static String assistantTool(String id) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\""
                + id + "\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"value\\\":\\\"hello\\\"}\"}}]}}]}";
    }

    private static String assistantText(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    private static void sendIgnoringDisconnect(HttpExchange exchange, int status, String body) {
        try { send(exchange, status, body); }
        catch (IOException ignored) { exchange.close(); }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        else exchange.close();
    }

    private final class Fixture {
        final FakeHostSystem host = new FakeHostSystem();
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        String stdin;
        private Path tokenFile;
        private Path providerTokenFile;

        Fixture(String stdin) { this.stdin = stdin; }

        Path mcpTokenFile() {
            try {
                if (tokenFile == null) {
                    tokenFile = Files.createTempFile("agent mcp token ", ".txt");
                    makePrivate(tokenFile);
                    Files.writeString(tokenFile, "mcp-secret", StandardCharsets.UTF_8);
                }
                return tokenFile;
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }

        Path providerTokenFile() {
            try {
                if (providerTokenFile == null) {
                    providerTokenFile = Files.createTempFile("agent provider token ", ".txt");
                    makePrivate(providerTokenFile);
                    Files.writeString(providerTokenFile, "provider-secret\n", StandardCharsets.UTF_8);
                }
                return providerTokenFile;
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }

        private void makePrivate(Path path) throws IOException {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // ACL-based test platform.
            }
        }

        void reset(String input) {
            output = new StringWriter();
            errors = new StringWriter();
            stdin = input;
        }

        int execute(String... args) {
            CliServices services = new CliServices(host, new EdtInstallationDiscovery(host),
                    new CliConfiguration(host), endpoint -> new EndpointProbe.ProbeResult(true, 200, "HTTP 200"),
                    new PrintWriter(output, true), new PrintWriter(errors, true), new StringReader(stdin), "9.8.7");
            return CodePilotCli.execute(services, args);
        }

        String out() { return output.toString().replace("\r\n", "\n"); }
        String err() { return errors.toString().replace("\r\n", "\n"); }
    }
}
