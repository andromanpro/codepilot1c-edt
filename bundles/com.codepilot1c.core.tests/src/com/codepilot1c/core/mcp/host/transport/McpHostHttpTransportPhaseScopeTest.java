package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.Test;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.logging.VibeLogger.Level;
import com.codepilot1c.core.logging.VibeLogger.LogEntry;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;

public class McpHostHttpTransportPhaseScopeTest {

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," //$NON-NLS-1$
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"phase\",\"version\":\"1\"}}}"; //$NON-NLS-1$

    @Test
    public void preRouteBrokenPipeUsesNormalErrorResponse() throws Exception {
        TestExchange exchange = new TestExchange(throwingInput(new SocketException("Broken pipe")), //$NON-NLS-1$
                new ByteArrayOutputStream());

        try (LogCapture logs = new LogCapture()) {
            handler(router()).handle(exchange);

            assertEquals(List.of(Integer.valueOf(500)), exchange.statusCodes);
            assertTrue(exchange.responseText().contains("internal_error")); //$NON-NLS-1$
            assertTrue(logs.has(Level.ERROR, "Unhandled MCP HTTP handler error")); //$NON-NLS-1$
            assertFalse(logs.has(Level.INFO, "client disconnected before response completed")); //$NON-NLS-1$
        }
    }

    @Test
    public void routerWrappedBrokenPipeUsesNormalErrorResponse() throws Exception {
        SocketException disconnect = new SocketException("Connection reset by peer"); //$NON-NLS-1$
        McpHostRequestRouter router = new TestRouter() {
            @Override
            public McpMessage route(McpMessage request, McpHostSession session) {
                throw new IllegalStateException("router failed", disconnect); //$NON-NLS-1$
            }
        };
        TestExchange exchange = exchange(new ByteArrayOutputStream());

        try (LogCapture logs = new LogCapture()) {
            handler(router).handle(exchange);

            assertEquals(List.of(Integer.valueOf(500)), exchange.statusCodes);
            assertTrue(exchange.responseText().contains("internal_error")); //$NON-NLS-1$
            assertTrue(logs.has(Level.ERROR, "Unhandled MCP HTTP handler error")); //$NON-NLS-1$
            assertFalse(logs.has(Level.INFO, "client disconnected before response completed")); //$NON-NLS-1$
        }
    }

    @Test
    public void bodyWriteBrokenPipeIsExpectedDisconnectWithoutSecondResponse() throws Exception {
        TestExchange exchange = exchange(new FailingOutputStream(new SocketException("Broken pipe"))); //$NON-NLS-1$

        try (LogCapture logs = new LogCapture()) {
            handler(router()).handle(exchange);

            assertEquals(List.of(Integer.valueOf(200)), exchange.statusCodes);
            assertEquals(1, exchange.responseBodyRequests);
            assertTrue(logs.has(Level.INFO, "client disconnected before response completed")); //$NON-NLS-1$
            assertTrue(logs.has(Level.INFO, "httpMethod=POST rpcMethod=initialize requestId=1")); //$NON-NLS-1$
            assertFalse(logs.has(Level.ERROR, "MCP HTTP response delivery error")); //$NON-NLS-1$
        }
    }

    @Test
    public void unexpectedBodyWriteIoUsesErrorPath() throws Exception {
        TestExchange exchange = exchange(new FailingOutputStream(new IOException("disk unavailable"))); //$NON-NLS-1$

        try (LogCapture logs = new LogCapture()) {
            handler(router()).handle(exchange);

            assertEquals(List.of(Integer.valueOf(200)), exchange.statusCodes);
            assertEquals(1, exchange.responseBodyRequests);
            assertTrue(logs.has(Level.ERROR, "MCP HTTP response delivery error")); //$NON-NLS-1$
            assertFalse(logs.has(Level.INFO, "client disconnected before response completed")); //$NON-NLS-1$
        }
    }

    private static HttpHandler handler(McpHostRequestRouter router) {
        McpHostHttpTransport transport = new McpHostHttpTransport("127.0.0.1", 0, //$NON-NLS-1$
                new McpHostOAuthService("127.0.0.1", 0, ""), router, //$NON-NLS-1$ //$NON-NLS-2$
                McpHostConfig.AuthMode.NONE, Duration.ofMinutes(1), Clock.systemUTC());
        return transport.createMcpHandler();
    }

    private static McpHostRequestRouter router() {
        return new TestRouter();
    }

    private static TestExchange exchange(OutputStream responseBody) {
        return new TestExchange(new ByteArrayInputStream(INITIALIZE.getBytes(StandardCharsets.UTF_8)), responseBody);
    }

    private static InputStream throwingInput(IOException failure) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }
        };
    }

    private static class TestRouter extends McpHostRequestRouter {
        TestRouter() {
            super(new AllowAllExposurePolicy(), List.of(), new EmptyPromptProvider(),
                    McpHostConfig.MutationPolicy.ALLOW);
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

    private static final class FailingOutputStream extends OutputStream {
        private final IOException failure;

        FailingOutputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) throws IOException {
            throw failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            throw failure;
        }
    }

    private static final class LogCapture implements AutoCloseable, Consumer<LogEntry> {
        private final List<LogEntry> entries = new ArrayList<>();

        LogCapture() {
            VibeLogger.getInstance().addListener(this);
        }

        @Override
        public void accept(LogEntry entry) {
            if (McpHostHttpTransport.class.getSimpleName().equals(entry.getCategory())) {
                entries.add(entry);
            }
        }

        boolean has(Level level, String fragment) {
            return entries.stream().anyMatch(entry -> entry.getLevel() == level
                    && entry.getMessage().contains(fragment));
        }

        @Override
        public void close() {
            VibeLogger.getInstance().removeListener(this);
        }
    }

    private static final class TestExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        private InputStream requestBody;
        private OutputStream responseBody;
        private final List<Integer> statusCodes = new ArrayList<>();
        private int responseBodyRequests;

        TestExchange(InputStream requestBody, OutputStream responseBody) {
            this.requestBody = requestBody;
            this.responseBody = responseBody;
        }

        String responseText() {
            return responseBody instanceof ByteArrayOutputStream bytes
                    ? bytes.toString(StandardCharsets.UTF_8)
                    : ""; //$NON-NLS-1$
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/mcp"); //$NON-NLS-1$
        }

        @Override
        public String getRequestMethod() {
            return "POST"; //$NON-NLS-1$
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            responseBodyRequests++;
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int responseCode, long responseLength) {
            statusCodes.add(Integer.valueOf(responseCode));
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
        }

        @Override
        public int getResponseCode() {
            return statusCodes.isEmpty() ? -1 : statusCodes.get(statusCodes.size() - 1).intValue();
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1"; //$NON-NLS-1$
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            requestBody = input;
            responseBody = output;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
