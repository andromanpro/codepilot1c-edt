package com.codepilot1c.runtime.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Minimal, asynchronous Streamable HTTP MCP client for the standalone harness.
 * It creates a session only from a successful initialize response.
 */
public final class McpClient implements AutoCloseable {
    public static final String CLIENT_NAME = "codepilot1c-cli";
    public static final String CLIENT_VERSION = "1.0.0";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final String ACCEPT_HEADER = "application/json, text/event-stream";

    private final McpClientConfig config;
    private final HttpClient httpClient;
    private final AtomicLong ids = new AtomicLong(1);
    private final Object stateLock = new Object();
    private volatile String sessionId;
    private volatile String negotiatedProtocol;
    /** A server-created session that must be deleted even when initialize result parsing fails. */
    private volatile String cleanupSessionId;
    private volatile String cleanupProtocol;
    private volatile boolean closed;
    private CompletableFuture<InitializeResult> initialization;

    public McpClient(McpClientConfig config) {
        this(java.util.Objects.requireNonNull(config, "config"),
                HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build());
    }

    McpClient(McpClientConfig config, HttpClient httpClient) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
    }

    /** GET /health/ready; a 503 is a valid not-ready result, not a client failure. */
    public CompletableFuture<HealthReadyResult> healthReady() {
        ensureUsable();
        HttpRequest.Builder builder = HttpRequest.newBuilder(healthEndpoint())
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .GET();
        return mapCancellable(execute(builder.build()), response -> {
            JsonObject body = parseOptionalObject(response.body());
            return new HealthReadyResult(response.statusCode(), response.statusCode() == 200, body);
        });
    }

    /** Negotiates a protocol and captures the required server-created session. */
    public CompletableFuture<InitializeResult> initialize() {
        synchronized (stateLock) {
            if (closed) return failed(stateError("MCP client is closed"));
            if (sessionId != null) {
                return failed(stateError("MCP client is already initialized"));
            }
            if (cleanupSessionId != null) {
                return failed(stateError("MCP client must be closed after failed initialize"));
            }
            if (initialization != null) return initialization;
            List<String> candidates = protocolCandidates(config.protocolPreferences());
            initialization = initializeAttempt(candidates, 0);
            initialization.whenComplete((result, error) -> {
                synchronized (stateLock) {
                    if (error != null) initialization = null;
                }
            });
            return initialization;
        }
    }

    public CompletableFuture<ToolsListResult> listTools() {
        return composeCancellable(requireSession(), ignored ->
                mapCancellable(request("tools/list", new JsonObject()), this::parseToolsList));
    }

    public CompletableFuture<ToolCallResult> callTool(String name, JsonObject arguments) {
        if (name == null || name.isBlank()) {
            return failed(new McpClientException(McpClientException.Kind.PROTOCOL,
                    "Tool name is required"));
        }
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments == null ? new JsonObject() : arguments.deepCopy());
        return composeCancellable(requireSession(), ignored ->
                mapCancellable(request("tools/call", params), this::parseToolCall));
    }

    public CompletableFuture<Void> ping() {
        return composeCancellable(requireSession(), ignored ->
                mapCancellable(request("ping", new JsonObject()), ignoredResult -> null));
    }

    public boolean isInitialized() { return sessionId != null && !closed; }
    public String negotiatedProtocol() { return negotiatedProtocol; }

    /** Sends DELETE for the known session, then clears session and bearer state. */
    public CompletableFuture<Void> closeAsync() {
        final String session;
        final String protocol;
        synchronized (stateLock) {
            if (closed) return CompletableFuture.completedFuture(null);
            closed = true;
            session = sessionId != null ? sessionId : cleanupSessionId;
            protocol = sessionId != null ? negotiatedProtocol : cleanupProtocol;
        }
        if (session == null) {
            clearState();
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.endpoint())
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header(SESSION_HEADER, session)
                .header(PROTOCOL_HEADER, protocol)
                .DELETE();
        addAuthorization(builder);
        return execute(builder.build()).handle((response, error) -> {
            clearState();
            // Closing is best effort: the local session is always forgotten, even if
            // the server has already expired it or the connection is unavailable.
            return (Void) null;
        });
    }

    @Override
    public void close() {
        try {
            closeAsync().join();
        } catch (CompletionException ignored) {
            // AutoCloseable cleanup remains best effort and never exposes bodies or credentials.
            clearState();
        }
    }

    private CompletableFuture<InitializeResult> initializeAttempt(List<String> candidates, int index) {
        if (index >= candidates.size()) {
            return failed(new McpClientException(McpClientException.Kind.PROTOCOL,
                    "MCP server rejected all supported protocol versions"));
        }
        String protocol = candidates.get(index);
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", protocol);
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", CLIENT_NAME);
        clientInfo.addProperty("version", CLIENT_VERSION);
        params.add("clientInfo", clientInfo);
        return request("initialize", params, protocol, null).handle((envelope, failure) -> {
            if (failure == null) {
                try {
                    InitializeResult result = parseInitialize(envelope, protocol);
                    synchronized (stateLock) {
                        if (closed) throw stateError("MCP client was closed during initialize");
                        sessionId = envelope.sessionId();
                        negotiatedProtocol = result.protocolVersion();
                        cleanupSessionId = null;
                        cleanupProtocol = null;
                    }
                    return CompletableFuture.completedFuture(result);
                } catch (McpClientException e) {
                    return McpClient.<InitializeResult>failed(e);
                }
            }
            McpClientException exception = unwrap(failure);
            if (exception.isUnsupportedProtocol() && index + 1 < candidates.size()) {
                return initializeAttempt(candidates, index + 1);
            }
            return McpClient.<InitializeResult>failed(exception);
        }).thenCompose(value -> value);
    }

    private InitializeResult parseInitialize(HttpEnvelope envelope, String requestedProtocol)
            throws McpClientException {
        if (envelope.sessionId() == null || envelope.sessionId().isBlank()) {
            throw new McpClientException(McpClientException.Kind.PROTOCOL,
                    "MCP initialize response did not provide Mcp-Session-Id");
        }
        JsonObject raw = requireObject(envelope.result(), "initialize result");
        String responseProtocol = stringValue(raw, "protocolVersion");
        if (responseProtocol == null || responseProtocol.isBlank()) responseProtocol = requestedProtocol;
        if (!McpClientConfig.SUPPORTED_PROTOCOLS.contains(responseProtocol)) {
            throw new McpClientException(McpClientException.Kind.PROTOCOL,
                    "MCP server selected unsupported protocol version");
        }
        return new InitializeResult(responseProtocol,
                objectValue(raw, "serverInfo"), objectValue(raw, "capabilities"),
                experimentalCodepilot(raw), raw);
    }

    private JsonObject experimentalCodepilot(JsonObject raw) {
        JsonObject experimental = objectValue(raw, "experimental");
        return experimental == null ? null : objectValue(experimental, "codepilot");
    }

    private ToolsListResult parseToolsList(HttpEnvelope envelope) {
        JsonObject raw = requireObjectUnchecked(envelope.result(), "tools/list result");
        JsonArray toolsArray = raw.getAsJsonArray("tools");
        if (toolsArray == null) throw protocolUnchecked("tools/list result has no tools array");
        List<ToolDefinition> tools = new ArrayList<>();
        for (JsonElement element : toolsArray) {
            if (!element.isJsonObject()) throw protocolUnchecked("tools/list contains a non-object tool");
            JsonObject tool = element.getAsJsonObject();
            String name = stringValue(tool, "name");
            if (name == null || name.isBlank()) throw protocolUnchecked("tools/list contains a nameless tool");
            tools.add(new ToolDefinition(name, stringValue(tool, "description"),
                    tool.get("inputSchema")));
        }
        return new ToolsListResult(tools, raw);
    }

    private ToolCallResult parseToolCall(HttpEnvelope envelope) {
        JsonObject raw = requireObjectUnchecked(envelope.result(), "tools/call result");
        return new ToolCallResult(raw.has("isError") && raw.get("isError").getAsBoolean(), raw);
    }

    private CompletableFuture<HttpEnvelope> request(String method, JsonObject params) {
        return request(method, params, negotiatedProtocol, sessionId);
    }

    private CompletableFuture<HttpEnvelope> request(String method, JsonObject params,
            String protocol, String session) {
        if (protocol == null || protocol.isBlank()) return failed(stateError("MCP protocol is not negotiated"));
        if (!"initialize".equals(method) && (session == null || session.isBlank())) {
            return failed(stateError("MCP session is not initialized"));
        }
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", ids.getAndIncrement());
        request.addProperty("method", method);
        request.add("params", params == null ? new JsonObject() : params.deepCopy());
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.endpoint())
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT_HEADER)
                .header(PROTOCOL_HEADER, protocol)
                .POST(HttpRequest.BodyPublishers.ofString(request.toString(), java.nio.charset.StandardCharsets.UTF_8));
        if (session != null && !session.isBlank()) builder.header(SESSION_HEADER, session);
        addAuthorization(builder);
        return mapCancellable(execute(builder.build()),
                response -> parseRpcResponse(response, session, protocol));
    }

    private CompletableFuture<HttpResponse<String>> execute(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> root = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> result = new CompletableFuture<>();
        root.whenComplete((response, error) -> {
            if (error != null) {
                result.completeExceptionally(new McpClientException(
                        McpClientException.Kind.TRANSPORT, "MCP HTTP request failed", unwrapCause(error)));
            } else {
                result.complete(response);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) root.cancel(true);
        });
        return result;
    }

    private HttpEnvelope parseRpcResponse(HttpResponse<String> response, String requestedSession, String requestedProtocol)
            throws CompletionException {
        String responseSession = response.headers().firstValue(SESSION_HEADER).orElse(requestedSession);
        if (response.statusCode() >= 200 && response.statusCode() < 300
                && requestedSession == null && responseSession != null && !responseSession.isBlank()) {
            rememberCleanupSession(responseSession, requestedProtocol);
        }
        String body = response.body() == null ? "" : response.body().trim();
        JsonObject object = parseObject(body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 404) forgetSession();
            throw new CompletionException(httpFailure(response.statusCode(), object));
        }
        if (object == null) {
            throw new CompletionException(new McpClientException(McpClientException.Kind.MALFORMED_RESPONSE,
                    "MCP response body is not a JSON object", response.statusCode(), Integer.MIN_VALUE, null));
        }
        if (object.has("error")) throw new CompletionException(rpcFailure(response.statusCode(), object));
        if (!object.has("result")) {
            throw new CompletionException(new McpClientException(McpClientException.Kind.MALFORMED_RESPONSE,
                    "MCP JSON-RPC response has no result", response.statusCode(), Integer.MIN_VALUE, null));
        }
        return new HttpEnvelope(response.statusCode(), object.get("result"), responseSession);
    }

    private McpClientException httpFailure(int status, JsonObject object) {
        if (object != null && object.has("error")) {
            JsonElement error = object.get("error");
            if (error.isJsonObject() && error.getAsJsonObject().has("code")) {
                return rpcFailure(status, object);
            }
            String text = error.isJsonPrimitive() ? error.getAsString() : "MCP HTTP error";
            if (text.toLowerCase(java.util.Locale.ROOT).contains("unsupported")
                    && text.toLowerCase(java.util.Locale.ROOT).contains("protocol")) {
                text = "Unsupported MCP protocol version";
            }
            return new McpClientException(McpClientException.Kind.HTTP, text, status,
                    Integer.MIN_VALUE, null);
        }
        return new McpClientException(McpClientException.Kind.HTTP,
                "MCP HTTP request returned status " + status, status, Integer.MIN_VALUE, null);
    }

    private McpClientException rpcFailure(int status, JsonObject object) {
        JsonObject error = object.getAsJsonObject("error");
        if (error == null) return new McpClientException(McpClientException.Kind.JSON_RPC,
                "MCP JSON-RPC error", status, Integer.MIN_VALUE, null);
        int code = error.has("code") && error.get("code").isJsonPrimitive()
                ? error.get("code").getAsInt() : Integer.MIN_VALUE;
        String message = error.has("message") && error.get("message").isJsonPrimitive()
                ? error.get("message").getAsString() : "MCP JSON-RPC error";
        return new McpClientException(McpClientException.Kind.JSON_RPC, message, status, code,
                error.get("data"));
    }

    private JsonObject parseOptionalObject(String body) {
        if (body == null || body.isBlank()) return null;
        return parseObject(body);
    }

    private JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) return null;
        String json = body;
        if (!json.startsWith("{")) {
            for (String line : json.split("\\R")) {
                if (line.startsWith("data:")) {
                    json = line.substring(5).trim();
                    break;
                }
            }
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    private URI healthEndpoint() {
        return config.endpoint().resolve("/health/ready");
    }

    private void addAuthorization(HttpRequest.Builder builder) {
        char[] token = config.copyBearerToken();
        if (token == null || token.length == 0) return;
        try {
            builder.header("Authorization", "Bearer " + new String(token));
        } finally {
            McpClientConfig.wipe(token);
        }
    }

    private CompletableFuture<Void> requireSession() {
        if (closed) return failed(stateError("MCP client is closed"));
        if (sessionId == null || negotiatedProtocol == null) {
            return failed(stateError("MCP client must be initialized before session methods"));
        }
        return CompletableFuture.completedFuture(null);
    }

    private void ensureUsable() {
        if (closed) throw new IllegalStateException("MCP client is closed");
    }

    private void clearState() {
        forgetSession();
        config.close();
    }

    private void forgetSession() {
        sessionId = null;
        negotiatedProtocol = null;
        cleanupSessionId = null;
        cleanupProtocol = null;
    }

    private void rememberCleanupSession(String session, String protocol) {
        synchronized (stateLock) {
            if (closed || sessionId != null) return;
            cleanupSessionId = session;
            cleanupProtocol = protocol;
        }
    }

    private static List<String> protocolCandidates(List<String> preferences) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>(preferences);
        values.addAll(McpClientConfig.SUPPORTED_PROTOCOLS);
        return List.copyOf(values);
    }

    private static JsonObject requireObject(JsonElement value, String label) throws McpClientException {
        if (value == null || !value.isJsonObject()) {
            throw new McpClientException(McpClientException.Kind.MALFORMED_RESPONSE,
                    label + " is not a JSON object");
        }
        return value.getAsJsonObject();
    }

    private static JsonObject requireObjectUnchecked(JsonElement value, String label) {
        try { return requireObject(value, label); }
        catch (McpClientException e) { throw new CompletionException(e); }
    }

    private static JsonObject objectValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String stringValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static McpClientException protocolUnchecked(String message) {
        return new McpClientException(McpClientException.Kind.PROTOCOL, message);
    }

    private static McpClientException stateError(String message) {
        return new McpClientException(McpClientException.Kind.STATE, message);
    }

    private static <S, T> CompletableFuture<T> mapCancellable(
            CompletableFuture<S> source, Function<S, T> mapper) {
        CompletableFuture<T> target = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure != null) {
                target.completeExceptionally(failure);
                return;
            }
            try {
                target.complete(mapper.apply(value));
            } catch (RuntimeException mappingFailure) {
                target.completeExceptionally(mappingFailure);
            }
        });
        target.whenComplete((ignored, failure) -> {
            if (target.isCancelled()) source.cancel(true);
        });
        return target;
    }

    private static <S, T> CompletableFuture<T> composeCancellable(
            CompletableFuture<S> source, Function<S, CompletableFuture<T>> mapper) {
        CompletableFuture<T> target = new CompletableFuture<>();
        AtomicReference<CompletableFuture<T>> child = new AtomicReference<>();
        source.whenComplete((value, failure) -> {
            if (failure != null) {
                target.completeExceptionally(failure);
                return;
            }
            if (target.isDone()) return;
            final CompletableFuture<T> mapped;
            try {
                mapped = java.util.Objects.requireNonNull(mapper.apply(value), "mapped future");
            } catch (RuntimeException mappingFailure) {
                target.completeExceptionally(mappingFailure);
                return;
            }
            child.set(mapped);
            if (target.isCancelled()) {
                mapped.cancel(true);
                return;
            }
            mapped.whenComplete((mappedValue, mappedFailure) -> {
                if (mappedFailure != null) target.completeExceptionally(mappedFailure);
                else target.complete(mappedValue);
            });
        });
        target.whenComplete((ignored, failure) -> {
            if (!target.isCancelled()) return;
            source.cancel(true);
            CompletableFuture<T> mapped = child.get();
            if (mapped != null) mapped.cancel(true);
        });
        return target;
    }

    private static <T> CompletableFuture<T> failed(McpClientException exception) {
        return CompletableFuture.failedFuture(exception);
    }

    private static McpClientException unwrap(Throwable failure) {
        Throwable value = failure;
        while (value instanceof CompletionException || value instanceof java.util.concurrent.ExecutionException) {
            if (value.getCause() == null) break;
            value = value.getCause();
        }
        return value instanceof McpClientException
                ? (McpClientException) value
                : new McpClientException(McpClientException.Kind.TRANSPORT,
                        "MCP request failed", value);
    }

    private static Throwable unwrapCause(Throwable failure) {
        return failure.getCause() == null ? failure : failure.getCause();
    }

    private record HttpEnvelope(int statusCode, JsonElement result, String sessionId) {}
}
