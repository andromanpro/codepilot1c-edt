/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.broker;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentModel;
import com.codepilot1c.runtime.agent.AgentModelException;
import com.codepilot1c.runtime.agent.CancellationToken;
import com.codepilot1c.runtime.agent.StreamObserver;
import com.codepilot1c.runtime.agent.ToolCall;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.mcp.McpClientConfig;
import com.codepilot1c.runtime.provider.SseEventParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Body-safe HTTP client for the frozen EDT {@code /llm/v1} broker contract.
 *
 * <p>The MCP endpoint and bearer token are deliberately constructor inputs.
 * Callers must pass the values selected by the existing MCP connection
 * resolver; this class defines no competing credential precedence. The token
 * is copied on construction and wiped on close.</p>
 */
public final class BrokerClient implements BrokerProbe, AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final String JSON = "application/json";
    private static final String SSE = "text/event-stream";
    private static final ScheduledThreadPoolExecutor PROBE_TIMEOUTS = probeTimeouts();

    private final HttpClient httpClient;
    private final URI capabilitiesEndpoint;
    private final URI chatEndpoint;
    private final Duration probeTimeout;
    private final Duration requestTimeout;
    private final Set<StreamOperation> operations = ConcurrentHashMap.newKeySet();
    private final Set<ProbeOperation> probes = ConcurrentHashMap.newKeySet();
    private char[] bearerToken;
    private boolean closed;

    /**
     * Creates a client from values already resolved by the MCP connection path.
     */
    public BrokerClient(URI mcpEndpoint, char[] bearerToken, boolean allowInsecureHttp) {
        this(HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
                mcpEndpoint, bearerToken, allowInsecureHttp,
                DEFAULT_PROBE_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    /** Constructor with injectable transport and timeouts for focused hosts/tests. */
    public BrokerClient(HttpClient httpClient, URI mcpEndpoint, char[] bearerToken,
            boolean allowInsecureHttp, Duration requestTimeout) {
        this(httpClient, mcpEndpoint, bearerToken, allowInsecureHttp,
                DEFAULT_PROBE_TIMEOUT, requestTimeout);
    }

    /** Constructor with independently injectable probe and model-turn timeouts. */
    public BrokerClient(HttpClient httpClient, URI mcpEndpoint, char[] bearerToken,
            boolean allowInsecureHttp, Duration probeTimeout, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(probeTimeout, "probeTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (probeTimeout.isZero() || probeTimeout.isNegative()) {
            throw new IllegalArgumentException("probeTimeout must be positive");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        validateMcpEndpoint(mcpEndpoint, allowInsecureHttp);
        this.capabilitiesEndpoint = brokerEndpoint(mcpEndpoint, "/llm/v1/capabilities");
        this.chatEndpoint = brokerEndpoint(mcpEndpoint, "/llm/v1/chat");
        this.probeTimeout = probeTimeout;
        this.requestTimeout = requestTimeout;
        this.bearerToken = bearerToken == null ? null : bearerToken.clone();
    }

    @Override
    public CompletionStage<BrokerInfo> probe() {
        ProbeOperation operation = new ProbeOperation();
        synchronized (this) {
            if (closed) return failed(transport("EDT broker client is closed"));
            probes.add(operation);
        }
        operation.result.whenComplete((value, failure) -> probes.remove(operation));
        operation.start();
        return operation.result;
    }

    CompletableFuture<AgentMessage.Assistant> complete(AgentModel.Request request,
            CancellationToken cancellation, StreamObserver observer) {
        if (cancellation.isCancelled()) return cancelled();
        JsonObject body;
        try {
            body = serialize(request);
        } catch (RuntimeException failure) {
            return failed(malformed("EDT broker request does not match schema v1"));
        }
        StreamOperation operation = new StreamOperation(body, cancellation, observer);
        synchronized (this) {
            if (closed) return failed(transport("EDT broker client is closed"));
            operations.add(operation);
        }
        operation.result.whenComplete((value, failure) -> operations.remove(operation));
        operation.start();
        return operation.result;
    }

    @Override
    public void close() {
        List<StreamOperation> active;
        List<ProbeOperation> activeProbes;
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (bearerToken != null) Arrays.fill(bearerToken, '\0');
            bearerToken = null;
            active = List.copyOf(operations);
            activeProbes = List.copyOf(probes);
        }
        activeProbes.forEach(ProbeOperation::cancel);
        active.forEach(StreamOperation::cancel);
    }

    int activeProbeCount() {
        return probes.size();
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        char[] token;
        synchronized (this) {
            if (closed) throw new IllegalStateException("broker client closed");
            token = bearerToken == null ? null : bearerToken.clone();
        }
        if (token == null || token.length == 0) return builder;
        try {
            return builder.header("Authorization", "Bearer " + new String(token));
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    private JsonObject serialize(AgentModel.Request request) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray messages = new JsonArray();
        for (AgentMessage message : request.messages()) messages.add(serializeMessage(message));
        root.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolDefinition tool : request.tools()) {
                JsonObject value = new JsonObject();
                value.addProperty("name", tool.name());
                value.addProperty("description", tool.description());
                value.add("inputSchema", tool.inputSchema());
                tools.add(value);
            }
            root.add("tools", tools);
        }
        return root;
    }

    private JsonObject serializeMessage(AgentMessage message) {
        JsonObject value = new JsonObject();
        if (message instanceof AgentMessage.Text text) {
            value.addProperty("role", text.role().name().toLowerCase(Locale.ROOT));
            value.addProperty("content", text.content());
        } else if (message instanceof AgentMessage.Assistant assistant) {
            value.addProperty("role", "assistant");
            if (assistant.text().isPresent()) value.addProperty("content", assistant.text().get());
            else value.add("content", JsonNull.INSTANCE);
            assistant.reasoning().ifPresent(reasoning -> value.addProperty("reasoning", reasoning));
            if (!assistant.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ToolCall call : assistant.toolCalls()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", call.id());
                    item.addProperty("name", call.name());
                    item.add("arguments", argument(call.argumentsJson()));
                    calls.add(item);
                }
                value.add("toolCalls", calls);
            }
        } else if (message instanceof AgentMessage.Tool tool) {
            value.addProperty("role", "tool");
            value.addProperty("toolCallId", tool.callId());
            value.addProperty("content", tool.result().toJson().toString());
        } else {
            throw new IllegalArgumentException("unsupported agent message");
        }
        return value;
    }

    private JsonElement argument(String argumentsJson) {
        try {
            return JsonParser.parseString(argumentsJson);
        } catch (JsonParseException failure) {
            return new com.google.gson.JsonPrimitive(argumentsJson);
        }
    }

    private BrokerInfo parseInfo(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            int schema = requiredInt(root, "schemaVersion");
            int maximum = requiredInt(root, "maxSchemaVersion");
            if (schema != SCHEMA_VERSION || maximum < SCHEMA_VERSION) {
                throw malformed("EDT broker uses an unsupported schema version");
            }
            JsonObject provider = requiredObject(root, "provider");
            return new BrokerInfo(schema, maximum,
                    requiredBoolean(root, "chat"), requiredBoolean(root, "streaming"),
                    new BrokerInfo.Provider(requiredString(provider, "id"),
                            requiredString(provider, "name"), requiredString(provider, "type"),
                            requiredString(provider, "model"),
                            requiredBoolean(provider, "streamingEnabled")));
        } catch (AgentModelException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw malformed("EDT broker capabilities do not match schema v1");
        }
    }

    private void requireSuccessful(int status) {
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403) {
            throw http(status, "EDT broker authentication failed");
        }
        if (status == 409) {
            throw http(status, "EDT LLM broker is busy; retry the request");
        }
        if (status == 503) {
            throw http(status, "No active LLM provider is configured; configure a provider in EDT and retry");
        }
        if (status == 400 || status == 404 || status == 405 || status == 422) {
            throw malformed("EDT broker protocol does not match schema v1");
        }
        throw http(status, "EDT broker returned an HTTP error");
    }

    private static void validateMcpEndpoint(URI endpoint, boolean allowInsecureHttp) {
        try (McpClientConfig ignored = McpClientConfig.builder(endpoint)
                .allowInsecureHttp(allowInsecureHttp).build()) {
            String path = endpoint.getPath();
            if (!(path == null || path.isBlank() || "/".equals(path)
                    || "/mcp".equals(path) || "/mcp/".equals(path))) {
                throw new IllegalArgumentException("endpoint must target MCP");
            }
        }
    }

    private static URI brokerEndpoint(URI source, String path) {
        try {
            return new URI(source.getScheme(), null, source.getHost(), source.getPort(), path, null, null);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("invalid MCP endpoint", failure);
        }
    }

    private static ScheduledThreadPoolExecutor probeTimeouts() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "codepilot-broker-probe-timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private final class ProbeOperation {
        private final CompletableFuture<BrokerInfo> result = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<HttpResponse<String>>> root =
                new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        ProbeOperation() {
            result.whenComplete((ignored, failure) -> {
                ScheduledFuture<?> scheduled = timeout.getAndSet(null);
                if (scheduled != null) scheduled.cancel(false);
                if (result.isCancelled()) cancelRoot();
            });
        }

        void start() {
            final HttpRequest request;
            try {
                request = authorized(HttpRequest.newBuilder(capabilitiesEndpoint)
                        .timeout(probeTimeout)
                        .header("Accept", JSON)
                        .GET()).build();
            } catch (RuntimeException failure) {
                result.completeExceptionally(transport("EDT broker probe could not be started"));
                return;
            }
            final CompletableFuture<HttpResponse<String>> requestFuture;
            try {
                requestFuture = httpClient.sendAsync(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                root.set(requestFuture);
            } catch (RuntimeException failure) {
                result.completeExceptionally(transport("EDT broker probe could not be started"));
                return;
            }
            if (result.isDone()) {
                requestFuture.cancel(true);
                return;
            }
            ScheduledFuture<?> scheduled = PROBE_TIMEOUTS.schedule(() -> {
                if (result.completeExceptionally(transport("EDT broker probe timed out"))) {
                    requestFuture.cancel(true);
                }
            }, probeTimeout.toNanos(), TimeUnit.NANOSECONDS);
            timeout.set(scheduled);
            if (result.isDone() && timeout.compareAndSet(scheduled, null)) scheduled.cancel(false);

            requestFuture.whenComplete((response, failure) -> {
                if (result.isDone()) return;
                if (failure != null) {
                    result.completeExceptionally(transport("EDT broker probe transport failed"));
                    return;
                }
                try {
                    requireSuccessful(response.statusCode());
                    result.complete(parseInfo(response.body()));
                } catch (RuntimeException protocolFailure) {
                    result.completeExceptionally(protocolFailure);
                }
            });
        }

        void cancel() {
            result.cancel(true);
            cancelRoot();
        }

        void cancelRoot() {
            CompletableFuture<HttpResponse<String>> requestFuture = root.get();
            if (requestFuture != null && !requestFuture.isDone()) requestFuture.cancel(true);
        }
    }

    private final class StreamOperation {
        private final JsonObject requestBody;
        private final CancellationToken cancellation;
        private final StreamObserver observer;
        private final CompletableFuture<AgentMessage.Assistant> result = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> root = new AtomicReference<>();
        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();
        private final AtomicReference<CancellationToken.Registration> registration = new AtomicReference<>();

        StreamOperation(JsonObject requestBody, CancellationToken cancellation, StreamObserver observer) {
            this.requestBody = requestBody;
            this.cancellation = cancellation;
            this.observer = observer;
            result.whenComplete((value, failure) -> {
                CancellationToken.Registration active = registration.getAndSet(null);
                if (active != null) active.close();
                if (result.isCancelled()) cancelTransport();
            });
        }

        void start() {
            try {
                CancellationToken.Registration active = cancellation.onCancel(this::cancel);
                registration.set(active);
                if (result.isDone() && registration.compareAndSet(active, null)) active.close();
                if (cancellation.isCancelled()) {
                    cancel();
                    return;
                }
                HttpRequest request = authorized(HttpRequest.newBuilder(chatEndpoint)
                        .timeout(requestTimeout)
                        .header("Accept", SSE)
                        .header("Content-Type", JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                requestBody.toString(), StandardCharsets.UTF_8))).build();
                CompletableFuture<HttpResponse<InputStream>> requestFuture = httpClient.sendAsync(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                root.set(requestFuture);
                if (result.isDone()) {
                    requestFuture.cancel(true);
                    return;
                }
                requestFuture.whenComplete(this::acceptResponse);
            } catch (RuntimeException failure) {
                fail(transport("EDT broker stream could not be started"));
            }
        }

        void acceptResponse(HttpResponse<InputStream> response, Throwable failure) {
            if (result.isDone()) {
                if (response != null) close(response.body());
                return;
            }
            if (failure != null || response == null || response.body() == null) {
                fail(transport("EDT broker stream transport failed"));
                return;
            }
            InputStream body = response.body();
            responseBody.set(body);
            if (result.isDone()) {
                close(body);
                return;
            }
            try {
                requireSuccessful(response.statusCode());
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).startsWith(SSE)) {
                    throw malformed("EDT broker did not return an SSE stream");
                }
            } catch (RuntimeException responseFailure) {
                close(body);
                fail(responseFailure);
                return;
            }
            CompletableFuture.runAsync(() -> read(body));
        }

        void read(InputStream body) {
            Accumulator accumulator = new Accumulator(observer);
            SseEventParser parser = new SseEventParser(accumulator::accept);
            char[] buffer = new char[257];
            try (Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8)) {
                while (!result.isDone() && !accumulator.done) {
                    int count = reader.read(buffer);
                    if (count < 0) break;
                    parser.accept(buffer, 0, count);
                }
                if (!result.isDone() && !accumulator.done) parser.finish();
                if (!result.isDone() && !accumulator.done) {
                    fail(transport("EDT broker stream disconnected before done"));
                } else if (!result.isDone()) {
                    result.complete(accumulator.assistant());
                }
            } catch (AgentModelException protocolFailure) {
                fail(protocolFailure);
            } catch (IOException failure) {
                if (!result.isCancelled()) fail(transport("EDT broker stream transport failed while reading"));
            } catch (RuntimeException failure) {
                fail(malformed("EDT broker stream does not match schema v1"));
            } finally {
                Arrays.fill(buffer, '\0');
                responseBody.compareAndSet(body, null);
                close(body);
            }
        }

        void cancel() {
            result.cancel(true);
            cancelTransport();
        }

        void cancelTransport() {
            CompletableFuture<HttpResponse<InputStream>> requestFuture = root.get();
            if (requestFuture != null && !requestFuture.isDone()) requestFuture.cancel(true);
            close(responseBody.getAndSet(null));
        }

        void fail(RuntimeException failure) {
            if (result.completeExceptionally(failure)) cancelTransport();
        }
    }

    private static final class Accumulator {
        private final StreamObserver observer;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private boolean reasoningPresent;
        private final List<ToolCall> calls = new ArrayList<>();
        private final Set<String> callIds = new HashSet<>();
        private boolean done;

        Accumulator(StreamObserver observer) {
            this.observer = observer;
        }

        void accept(SseEventParser.Event event) {
            JsonObject payload;
            try {
                payload = JsonParser.parseString(event.data()).getAsJsonObject();
            } catch (RuntimeException failure) {
                throw malformed("EDT broker SSE data is not valid schema v1 JSON");
            }
            if (requiredInt(payload, "schemaVersion") != SCHEMA_VERSION) {
                throw malformed("EDT broker SSE event uses an unsupported schema version");
            }
            switch (event.type()) {
                case "delta" -> delta(payload, false);
                case "reasoning" -> delta(payload, true);
                case "tool_calls" -> toolCalls(payload);
                case "usage" -> usage(payload);
                case "done" -> finish(payload);
                case "error" -> error(payload);
                default -> throw malformed("EDT broker sent an unknown SSE event");
            }
        }

        void delta(JsonObject payload, boolean reasoning) {
            ensureOpen();
            String fragment = requiredString(payload, "text");
            try {
                if (reasoning) {
                    reasoningPresent = true;
                    this.reasoning.append(fragment);
                    observer.onReasoningDelta(fragment);
                }
                else {
                    text.append(fragment);
                    observer.onAssistantDelta(fragment);
                }
            } catch (RuntimeException failure) {
                throw transport("EDT broker stream observer failed");
            }
        }

        void toolCalls(JsonObject payload) {
            ensureOpen();
            JsonArray values = requiredArray(payload, "toolCalls");
            for (JsonElement element : values) {
                if (!element.isJsonObject()) throw malformed("EDT broker tool call is malformed");
                JsonObject value = element.getAsJsonObject();
                String id = requiredString(value, "id");
                String name = requiredString(value, "name");
                JsonElement rawArguments = value.get("arguments");
                if (rawArguments == null || rawArguments.isJsonNull()) {
                    throw malformed("EDT broker tool call arguments are missing");
                }
                if (!callIds.add(id)) throw malformed("EDT broker returned duplicate tool call ids");
                String arguments = rawArguments.isJsonPrimitive()
                        && rawArguments.getAsJsonPrimitive().isString()
                        ? rawArguments.getAsString() : rawArguments.toString();
                calls.add(new ToolCall(id, name, arguments));
            }
        }

        void usage(JsonObject payload) {
            ensureOpen();
            nonNegative(payload, "promptTokens");
            nonNegative(payload, "completionTokens");
            nonNegative(payload, "totalTokens");
            if (payload.has("cacheReadInputTokens")) nonNegative(payload, "cacheReadInputTokens");
            if (payload.has("cacheCreationInputTokens")) nonNegative(payload, "cacheCreationInputTokens");
        }

        void finish(JsonObject payload) {
            ensureOpen();
            if (payload.has("finishReason")) requiredString(payload, "finishReason");
            done = true;
        }

        void error(JsonObject payload) {
            ensureOpen();
            requiredString(payload, "code");
            requiredString(payload, "message");
            throw transport("EDT broker reported a provider failure");
        }

        AgentMessage.Assistant assistant() {
            Optional<String> visible = text.length() == 0 ? Optional.empty() : Optional.of(text.toString());
            Optional<String> retainedReasoning = reasoningPresent
                    ? Optional.of(reasoning.toString()) : Optional.empty();
            try {
                return new AgentMessage.Assistant(visible, retainedReasoning, List.copyOf(calls));
            } catch (IllegalArgumentException failure) {
                throw malformed("EDT broker completed without assistant content");
            }
        }

        void ensureOpen() {
            if (done) throw malformed("EDT broker sent an event after done");
        }

        void nonNegative(JsonObject payload, String field) {
            int value = requiredInt(payload, field);
            if (value < 0) throw malformed("EDT broker usage is malformed");
        }
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) throw malformed("EDT broker field is malformed");
        return value.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw malformed("EDT broker field is malformed");
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw malformed("EDT broker field is malformed");
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject object, String field) {
        JsonElement value = object.get(field);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw malformed("EDT broker field is malformed");
            }
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw malformed("EDT broker field is malformed");
        }
    }

    private static boolean requiredBoolean(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw malformed("EDT broker field is malformed");
        }
        return value.getAsBoolean();
    }

    private static AgentModelException transport(String message) {
        return new AgentModelException(AgentModelException.Kind.TRANSPORT, message);
    }

    private static AgentModelException malformed(String message) {
        return new AgentModelException(AgentModelException.Kind.MALFORMED_RESPONSE, message);
    }

    private static AgentModelException http(int status, String message) {
        return new AgentModelException(AgentModelException.Kind.HTTP, message, status);
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    private static <T> CompletableFuture<T> cancelled() {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.cancel(false);
        return future;
    }

    private static void close(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing is best effort during cancellation and completion.
        }
    }
}
