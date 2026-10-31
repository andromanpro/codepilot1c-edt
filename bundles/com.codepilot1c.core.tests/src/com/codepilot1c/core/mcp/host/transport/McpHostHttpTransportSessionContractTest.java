package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * HTTP-level compatibility contracts for MCP session lifecycle behavior.
 */
public class McpHostHttpTransportSessionContractTest {

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"contract\",\"version\":\"1\"}}}"; //$NON-NLS-1$
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"; //$NON-NLS-1$
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"; //$NON-NLS-1$

    @Test
    public void initializeWithoutSessionIdCreatesSessionAndReturnsHeader() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> response = fixture.post(INITIALIZE, Map.of());

            assertEquals(200, response.statusCode());
            String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null); //$NON-NLS-1$
            assertNotNull(sessionId);
            assertFalse(sessionId.isBlank());
            assertEquals(1, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void unknownClientSessionIdReturns404WithoutCreatingSession() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> initialized = fixture.post(INITIALIZE, Map.of());
            assertEquals(200, initialized.statusCode());

            HttpResponse<String> response = fixture.post(PING, Map.of("Mcp-Session-Id", "unknown-session")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(404, response.statusCode());
            assertEquals(Map.of("error", "session_not_found"), json(response.body())); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void postWithoutSessionIdRequiresInitializeAndDoesNotCreateSession() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            assertEquals(200, fixture.post(INITIALIZE, Map.of()).statusCode());
            int existingSessionCount = fixture.transport.getSessionsSnapshot().size();

            HttpResponse<String> response = fixture.post(TOOLS_LIST, Map.of());

            assertEquals(400, response.statusCode());
            assertEquals(Map.of("error", "session_required"), json(response.body())); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(existingSessionCount, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void deleteTerminatesKnownSession() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> initialized = fixture.post(INITIALIZE, Map.of());
            String sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(); //$NON-NLS-1$

            HttpResponse<String> deleted = fixture.delete(Map.of("Mcp-Session-Id", sessionId)); //$NON-NLS-1$
            assertEquals(204, deleted.statusCode());
            assertTrue(fixture.transport.getSessionsSnapshot().isEmpty());

            assertEquals(404, fixture.post(PING, Map.of("Mcp-Session-Id", sessionId)).statusCode()); //$NON-NLS-1$
        }
    }

    @Test
    public void expiresSessionAtExactIdleTtlWithoutWallClockWaiting() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z")); //$NON-NLS-1$
        try (TransportFixture fixture = new TransportFixture(Duration.ofSeconds(60), clock)) {
            HttpResponse<String> initialized = fixture.post(INITIALIZE, Map.of());
            String sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(); //$NON-NLS-1$

            clock.advance(Duration.ofSeconds(60));
            fixture.transport.cleanupInactiveSessions();

            assertTrue(fixture.transport.getSessionsSnapshot().isEmpty());
            assertEquals(404, fixture.post(PING, Map.of("Mcp-Session-Id", sessionId)).statusCode()); //$NON-NLS-1$
        }
    }

    @Test
    public void acceptsMissingAndSupportedProtocolHeaderAndRejectsUnsupportedVersion() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            assertEquals(200, fixture.post(INITIALIZE, Map.of()).statusCode());
            assertEquals(200, fixture.post(INITIALIZE,
                    Map.of("MCP-Protocol-Version", "2025-06-18")).statusCode()); //$NON-NLS-1$ //$NON-NLS-2$

            HttpResponse<String> rejected = fixture.post(INITIALIZE,
                    Map.of("MCP-Protocol-Version", "1999-01-01")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(400, rejected.statusCode());
            assertEquals(Map.of(
                    "error", "unsupported_protocol_version", //$NON-NLS-1$ //$NON-NLS-2$
                    "message", "Unsupported MCP-Protocol-Version", //$NON-NLS-1$ //$NON-NLS-2$
                    "supported", List.of("2025-11-25", "2025-06-18", "2024-11-05")), json(rejected.body())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals(2, fixture.transport.getSessionsSnapshot().size());
        }
    }

    private static Map<String, Object> json(String value) {
        return new Gson().fromJson(value, new TypeToken<Map<String, Object>>() { }.getType());
    }

    private static final class TransportFixture implements AutoCloseable {
        private final McpHostHttpTransport transport;
        private final HttpClient client = HttpClient.newHttpClient();
        private final int port;

        TransportFixture(Duration ttl, Clock clock) {
            McpHostRequestRouter router = new McpHostRequestRouter(
                    new AllowAllExposurePolicy(), List.of(), new EmptyPromptProvider(),
                    McpHostConfig.MutationPolicy.ALLOW);
            transport = new McpHostHttpTransport("127.0.0.1", 0, //$NON-NLS-1$
                    new McpHostOAuthService("127.0.0.1", 0, ""), router, //$NON-NLS-1$ //$NON-NLS-2$
                    McpHostConfig.AuthMode.NONE, ttl, clock);
            transport.start();
            port = transport.getBoundPort();
        }

        HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = request(headers)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> delete(Map<String, String> headers) throws Exception {
            return client.send(request(headers).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(Map<String, String> headers) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")); //$NON-NLS-1$ //$NON-NLS-2$
            headers.forEach(request::header);
            return request;
        }

        @Override
        public void close() {
            transport.stop();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {
        @Override
        public boolean isExposed(String toolName) {
            return true;
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class EmptyPromptProvider implements IMcpPromptProvider {
        @Override
        public List<McpPrompt> listPrompts() {
            return List.of();
        }

        @Override
        public java.util.Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return java.util.Optional.empty();
        }
    }
}
