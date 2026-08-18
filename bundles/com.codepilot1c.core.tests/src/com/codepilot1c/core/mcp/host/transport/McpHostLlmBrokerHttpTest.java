package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.llm.LlmProviderMetadata;
import com.codepilot1c.core.mcp.host.llm.McpHostLlmBroker;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderException;
import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.ProviderType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** HTTP contracts for the connected-CLI LLM v1 broker. */
public class McpHostLlmBrokerHttpTest {

    private static final String TOKEN = "test-bearer-token"; //$NON-NLS-1$
    private static final String CHAT = """
            {"schemaVersion":1,
             "messages":[{"role":"system","content":"be exact"},{"role":"user","content":"hello"}],
             "tools":[{"name":"lookup","description":"Lookup","inputSchema":{"type":"object"}}],
             "options":{"maxTokens":123,"temperature":0.25,"toolChoice":"required"}}
            """;

    @Test
    public void exposesOnlyAllowlistedActiveProviderMetadata() throws Exception {
        LlmProviderConfig config = new LlmProviderConfig("provider-id", "Safe Name", //$NON-NLS-1$ //$NON-NLS-2$
                ProviderType.OPENAI_COMPATIBLE, "https://user:password@example.test/v1", //$NON-NLS-1$
                "api-key-secret", "safe-model", 1024); //$NON-NLS-1$ //$NON-NLS-2$
        config.setCustomHeaders(Map.of("Authorization", "Bearer header-secret", //$NON-NLS-1$ //$NON-NLS-2$
                "X-Token", "custom-token-secret")); //$NON-NLS-1$ //$NON-NLS-2$
        DynamicLlmProvider provider = new DynamicLlmProvider(config);

        try (Fixture fixture = new Fixture(McpHostConfig.AuthMode.NONE,
                new McpHostLlmBroker(true, () -> provider))) {
            HttpResponse<String> response = fixture.get("/llm/v1/capabilities", null); //$NON-NLS-1$

            assertEquals(200, response.statusCode());
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(1, json.get("schemaVersion").getAsInt()); //$NON-NLS-1$
            assertEquals(1, json.get("maxSchemaVersion").getAsInt()); //$NON-NLS-1$
            assertTrue(json.get("chat").getAsBoolean()); //$NON-NLS-1$
            assertTrue(json.get("streaming").getAsBoolean()); //$NON-NLS-1$
            JsonObject metadata = json.getAsJsonObject("provider"); //$NON-NLS-1$
            assertEquals(List.of("id", "name", "type", "model", "streamingEnabled"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                    metadata.keySet().stream().toList());
            assertEquals("provider-id", metadata.get("id").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("openai_compatible", metadata.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("safe-model", metadata.get("model").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(response.body().contains("api-key-secret")); //$NON-NLS-1$
            assertFalse(response.body().contains("user:password")); //$NON-NLS-1$
            assertFalse(response.body().contains("header-secret")); //$NON-NLS-1$
            assertFalse(response.body().contains("custom-token-secret")); //$NON-NLS-1$
        } finally {
            provider.dispose();
        }
    }

    @Test
    public void reusesMcpHostAuthModeMatrix() throws Exception {
        FakeProvider provider = FakeProvider.completed();
        try (Fixture none = fixture(McpHostConfig.AuthMode.NONE, provider);
             Fixture bearer = fixture(McpHostConfig.AuthMode.BEARER_ONLY, provider);
             Fixture either = fixture(McpHostConfig.AuthMode.OAUTH_OR_BEARER, provider);
             Fixture oauth = fixture(McpHostConfig.AuthMode.OAUTH_ONLY, provider)) {
            assertEquals(200, none.get("/llm/v1/capabilities", null).statusCode()); //$NON-NLS-1$
            assertEquals(401, bearer.get("/llm/v1/capabilities", null).statusCode()); //$NON-NLS-1$
            assertEquals(401, bearer.get("/llm/v1/capabilities", "wrong").statusCode()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(200, bearer.get("/llm/v1/capabilities", TOKEN).statusCode()); //$NON-NLS-1$
            assertEquals(200, either.get("/llm/v1/capabilities", TOKEN).statusCode()); //$NON-NLS-1$
            assertEquals(401, oauth.get("/llm/v1/capabilities", TOKEN).statusCode()); //$NON-NLS-1$
            assertTrue(bearer.get("/llm/v1/capabilities", null).headers() //$NON-NLS-1$
                    .firstValue("WWW-Authenticate").isPresent()); //$NON-NLS-1$
        }
    }

    @Test
    public void mapsNormalizedRequestAndAllProviderChunkTypesToSse() throws Exception {
        FakeProvider provider = FakeProvider.mapping();
        try (Fixture fixture = fixture(McpHostConfig.AuthMode.NONE, provider)) {
            HttpResponse<String> response = fixture.post(CHAT, null);

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("") //$NON-NLS-1$ //$NON-NLS-2$
                    .startsWith("text/event-stream")); //$NON-NLS-1$
            assertTrue(response.body().contains("event: delta\ndata: {\"text\":\"answer\",\"schemaVersion\":1}")); //$NON-NLS-1$
            assertTrue(response.body().contains("event: reasoning\ndata: {\"text\":\"thinking\",\"schemaVersion\":1}")); //$NON-NLS-1$
            assertTrue(response.body().contains("event: tool_calls")); //$NON-NLS-1$
            assertTrue(response.body().contains("\"arguments\":\"{\\\"key\\\":\\\"value\\\"}\"")); //$NON-NLS-1$
            assertTrue(response.body().contains("event: usage")); //$NON-NLS-1$
            assertTrue(response.body().contains("\"inputTokens\":3")); //$NON-NLS-1$
            assertTrue(response.body().contains("\"outputTokens\":4")); //$NON-NLS-1$
            assertFalse(response.body().contains("promptTokens")); //$NON-NLS-1$
            assertTrue(response.body().contains("event: done\ndata: {\"finishReason\":\"tool_use\",\"schemaVersion\":1}")); //$NON-NLS-1$

            LlmRequest normalized = provider.request.get();
            assertNotNull(normalized);
            assertEquals(2, normalized.getMessages().size());
            assertEquals(1, normalized.getTools().size());
            assertEquals("{\"type\":\"object\"}", normalized.getTools().get(0).getParametersSchema()); //$NON-NLS-1$
            assertEquals(123, normalized.getMaxTokens());
            assertEquals(0.25, normalized.getTemperature(), 0.0001);
            assertEquals(LlmRequest.ToolChoice.REQUIRED, normalized.getToolChoice());
            assertTrue(normalized.isStream());
            assertEquals(null, normalized.getModel());
        }
    }

    @Test
    public void rejectsUnknownSchemaAndModelOverridesBeforeStartingProvider() throws Exception {
        FakeProvider provider = FakeProvider.completed();
        try (Fixture fixture = fixture(McpHostConfig.AuthMode.NONE, provider)) {
            HttpResponse<String> schema = fixture.post(
                    "{\"schemaVersion\":2,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}", null); //$NON-NLS-1$
            assertError(schema, 422, "unsupported_schema_version"); //$NON-NLS-1$

            HttpResponse<String> model = fixture.post(
                    "{\"schemaVersion\":1,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],"
                    + "\"options\":{\"model\":\"other-model\"}}", null); //$NON-NLS-1$ //$NON-NLS-2$
            assertError(model, 422, "model_override_unsupported"); //$NON-NLS-1$
            assertEquals(0, provider.invocations.get());
        }
    }

    @Test
    public void emitsFrozenTypedProviderErrorWithOptionalHttpStatusAndNoBodyLeak() throws Exception {
        FakeProvider provider = new FakeProvider(false) {
            @Override
            public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
                throw new LlmProviderException("provider-body-secret", null, 429, null); //$NON-NLS-1$
            }
        };
        try (Fixture fixture = fixture(McpHostConfig.AuthMode.NONE, provider)) {
            HttpResponse<String> response = fixture.post(CHAT, null);

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"code\":\"PROVIDER_HTTP\"")); //$NON-NLS-1$
            assertTrue(response.body().contains("\"status\":429")); //$NON-NLS-1$
            assertFalse(response.body().contains("provider-body-secret")); //$NON-NLS-1$
        }
    }

    @Test
    public void returnsProviderUnavailableAndDisabledEndpointsAreHidden() throws Exception {
        try (Fixture unavailable = new Fixture(McpHostConfig.AuthMode.NONE,
                    new McpHostLlmBroker(true, () -> null));
             Fixture disabled = new Fixture(McpHostConfig.AuthMode.BEARER_ONLY,
                    new McpHostLlmBroker(false, () -> FakeProvider.completed()))) {
            assertError(unavailable.get("/llm/v1/capabilities", null), //$NON-NLS-1$
                    503, "provider_unavailable"); //$NON-NLS-1$
            assertError(unavailable.post(CHAT, null), 503, "provider_unavailable"); //$NON-NLS-1$
            assertError(disabled.get("/llm/v1/capabilities", null), 404, "not_found"); //$NON-NLS-1$ //$NON-NLS-2$
            assertError(disabled.post(CHAT, null), 404, "not_found"); //$NON-NLS-1$
        }
    }

    @Test
    public void enforcesRaceSafeSingleFlight() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        try (Fixture fixture = fixture(McpHostConfig.AuthMode.NONE, provider)) {
            CompletableFuture<HttpResponse<InputStream>> first = fixture.postStreaming(CHAT);
            assertTrue(provider.started.await(2, TimeUnit.SECONDS));
            assertNotNull(first.get(2, TimeUnit.SECONDS));

            assertError(fixture.post(CHAT, null), 409, "busy"); //$NON-NLS-1$
            fixture.broker.cancelActive();
            assertTrue(provider.returned.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void disconnectCancelsOnlyOwnedFlightAndAlwaysReleasesSlot() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        try (Fixture fixture = new Fixture(McpHostConfig.AuthMode.NONE,
                broker(true, provider, Duration.ofMillis(20)))) {
            Socket abandoned = fixture.openRawChat(CHAT);
            assertTrue(provider.started.await(2, TimeUnit.SECONDS));
            abandoned.close();
            assertTrue(provider.returned.await(3, TimeUnit.SECONDS));
            assertEquals(1, provider.cancelCount.get());

            provider.resetForNextInvocation();
            CompletableFuture<HttpResponse<InputStream>> second = fixture.postStreaming(CHAT);
            assertTrue(provider.started.await(2, TimeUnit.SECONDS));
            assertNotNull(second.get(2, TimeUnit.SECONDS));
            Thread.sleep(120);
            assertEquals("A stale disconnect must not cancel the newer flight", 1, provider.cancelCount.get()); //$NON-NLS-1$

            fixture.broker.cancelActive();
            assertTrue(provider.returned.await(2, TimeUnit.SECONDS));
            assertEquals(2, provider.cancelCount.get());
        }
    }

    private static McpHostLlmBroker broker(boolean enabled, ILlmProvider provider, Duration keepalive) {
        return new McpHostLlmBroker(enabled, () -> provider,
                ignored -> new LlmProviderMetadata("fake", "Fake", "test", "fake-model", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                keepalive);
    }

    private static Fixture fixture(McpHostConfig.AuthMode authMode, ILlmProvider provider) {
        return new Fixture(authMode, broker(true, provider, Duration.ofSeconds(15)));
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertEquals(code, JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString()); //$NON-NLS-1$
    }

    private static final class Fixture implements AutoCloseable {
        private final McpHostLlmBroker broker;
        private final McpHostHttpTransport transport;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private final int port;

        Fixture(McpHostConfig.AuthMode authMode, McpHostLlmBroker broker) {
            this.broker = broker;
            McpHostRequestRouter router = new McpHostRequestRouter(
                    new AllowAllExposurePolicy(), List.of(), new EmptyPromptProvider(),
                    McpHostConfig.MutationPolicy.ALLOW);
            transport = new McpHostHttpTransport("127.0.0.1", 0, //$NON-NLS-1$
                    new McpHostOAuthService("127.0.0.1", 0, //$NON-NLS-1$
                            authMode == McpHostConfig.AuthMode.OAUTH_ONLY ? "" : TOKEN), //$NON-NLS-1$
                    router, authMode,
                    Duration.ofMinutes(1), broker);
            transport.start();
            port = transport.getBoundPort();
        }

        HttpResponse<String> get(String path, String token) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
            authorize(builder, token);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String body, String token) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/llm/v1/chat")) //$NON-NLS-1$
                    .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            authorize(builder, token);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        CompletableFuture<HttpResponse<InputStream>> postStreaming(String body) {
            HttpRequest request = HttpRequest.newBuilder(uri("/llm/v1/chat")) //$NON-NLS-1$
                    .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        }

        Socket openRawChat(String body) throws Exception {
            Socket socket = new Socket("127.0.0.1", port); //$NON-NLS-1$
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "POST /llm/v1/chat HTTP/1.1\r\n" //$NON-NLS-1$
                    + "Host: 127.0.0.1\r\n" //$NON-NLS-1$
                    + "Content-Type: application/json\r\n" //$NON-NLS-1$
                    + "Content-Length: " + bytes.length + "\r\n\r\n"; //$NON-NLS-1$ //$NON-NLS-2$
            OutputStream output = socket.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
            return socket;
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path); //$NON-NLS-1$
        }

        private void authorize(HttpRequest.Builder builder, String token) {
            if (token != null) {
                builder.header("Authorization", "Bearer " + token); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        @Override
        public void close() {
            transport.stop();
        }
    }

    private static class FakeProvider implements ILlmProvider {
        protected final AtomicReference<LlmRequest> request = new AtomicReference<>();
        protected final AtomicInteger invocations = new AtomicInteger();
        private final boolean mapping;

        FakeProvider(boolean mapping) {
            this.mapping = mapping;
        }

        static FakeProvider completed() {
            return new FakeProvider(false);
        }

        static FakeProvider mapping() {
            return new FakeProvider(true);
        }

        @Override
        public String getId() {
            return "fake"; //$NON-NLS-1$
        }

        @Override
        public String getDisplayName() {
            return "Fake"; //$NON-NLS-1$
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            return CompletableFuture.completedFuture(LlmResponse.of("unused")); //$NON-NLS-1$
        }

        @Override
        public void streamComplete(LlmRequest value, Consumer<LlmStreamChunk> consumer) {
            request.set(value);
            invocations.incrementAndGet();
            if (mapping) {
                consumer.accept(LlmStreamChunk.content("answer")); //$NON-NLS-1$
                consumer.accept(LlmStreamChunk.reasoning("thinking")); //$NON-NLS-1$
                consumer.accept(LlmStreamChunk.toolCalls(
                        List.of(new ToolCall("call-1", "lookup", "{\"key\":\"value\"}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                consumer.accept(LlmStreamChunk.usage(new LlmResponse.Usage(3, 4, 7)));
                consumer.accept(LlmStreamChunk.complete("tool_use")); //$NON-NLS-1$
            } else {
                consumer.accept(LlmStreamChunk.complete("stop")); //$NON-NLS-1$
            }
        }

        @Override
        public void cancel() {
            // No request remains active in this provider.
        }

        @Override
        public void dispose() {
            // Nothing to dispose.
        }
    }

    private static final class BlockingProvider extends FakeProvider {
        private final AtomicInteger cancelCount = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile CountDownLatch started = new CountDownLatch(1);
        private volatile CountDownLatch returned = new CountDownLatch(1);

        BlockingProvider() {
            super(false);
        }

        @Override
        public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
            super.request.set(request);
            super.invocations.incrementAndGet();
            started.countDown();
            try {
                while (!cancelled.get()) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                returned.countDown();
            }
        }

        @Override
        public void cancel() {
            cancelCount.incrementAndGet();
            cancelled.set(true);
        }

        void resetForNextInvocation() {
            cancelled.set(false);
            started = new CountDownLatch(1);
            returned = new CountDownLatch(1);
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
        public Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return Optional.empty();
        }
    }
}
