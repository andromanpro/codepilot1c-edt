package com.codepilot1c.core.mcp.host.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;

/**
 * Versioned, provider-neutral streaming bridge from the MCP host HTTP server
 * to the active plugin LLM provider.
 */
public final class McpHostLlmBroker {

    public static final int SCHEMA_VERSION = 1;
    public static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(15);

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostLlmBroker.class);
    private static final String SSE_CONTENT_TYPE = "text/event-stream; charset=utf-8"; //$NON-NLS-1$

    private final boolean enabled;
    private final Supplier<ILlmProvider> providerSupplier;
    private final LlmProviderMetadataResolver metadataResolver;
    private final Duration keepaliveInterval;
    private final AtomicReference<Flight> activeFlight = new AtomicReference<>();
    private final Gson gson = new Gson();

    public McpHostLlmBroker(boolean enabled) {
        this(enabled, () -> LlmProviderRegistry.getInstance().getActiveProvider());
    }

    public McpHostLlmBroker(boolean enabled, Supplier<ILlmProvider> providerSupplier) {
        this(enabled, providerSupplier, LlmProviderMetadataResolver.defaults(), KEEPALIVE_INTERVAL);
    }

    /** Constructor with injectable metadata and heartbeat timing for focused tests. */
    public McpHostLlmBroker(boolean enabled, Supplier<ILlmProvider> providerSupplier,
            LlmProviderMetadataResolver metadataResolver, Duration keepaliveInterval) {
        this.enabled = enabled;
        this.providerSupplier = providerSupplier;
        this.metadataResolver = metadataResolver != null
                ? metadataResolver : LlmProviderMetadataResolver.defaults();
        this.keepaliveInterval = keepaliveInterval != null && !keepaliveInterval.isZero()
                && !keepaliveInterval.isNegative() ? keepaliveInterval : KEEPALIVE_INTERVAL;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void handleCapabilities(HttpExchange exchange) throws IOException {
        ILlmProvider provider = activeProvider();
        if (provider == null) {
            writeError(exchange, 503, "provider_unavailable", "No active LLM provider is available"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        final LlmProviderMetadata metadata;
        try {
            metadata = metadataResolver.resolve(provider);
        } catch (Exception e) {
            LOG.warn("Failed to resolve safe LLM provider metadata: %s", e.getClass().getSimpleName()); //$NON-NLS-1$
            writeError(exchange, 500, "internal_error", "Provider metadata is unavailable"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        JsonObject providerJson = new JsonObject();
        providerJson.addProperty("id", metadata.id()); //$NON-NLS-1$
        providerJson.addProperty("name", metadata.name()); //$NON-NLS-1$
        providerJson.addProperty("type", metadata.type()); //$NON-NLS-1$
        providerJson.addProperty("model", metadata.model()); //$NON-NLS-1$
        providerJson.addProperty("streamingEnabled", metadata.streamingEnabled()); //$NON-NLS-1$

        JsonObject response = new JsonObject();
        response.addProperty("schemaVersion", SCHEMA_VERSION); //$NON-NLS-1$
        response.addProperty("maxSchemaVersion", SCHEMA_VERSION); //$NON-NLS-1$
        response.addProperty("chat", true); //$NON-NLS-1$
        response.addProperty("streaming", true); //$NON-NLS-1$
        response.add("provider", providerJson); //$NON-NLS-1$
        writeJson(exchange, 200, response);
    }

    public void handleChat(HttpExchange exchange) throws IOException {
        JsonObject payload;
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                writeError(exchange, 400, "invalid_request", "Request body must be a JSON object"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            payload = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            writeError(exchange, 400, "invalid_request", "Request body is not valid JSON"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        final int schemaVersion;
        try {
            schemaVersion = integer(payload, "schemaVersion", 0); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            writeError(exchange, 422, "unsupported_schema_version", //$NON-NLS-1$
                    "Only LLM broker schema version 1 is supported"); //$NON-NLS-1$
            return;
        }
        if (schemaVersion != SCHEMA_VERSION) {
            writeError(exchange, 422, "unsupported_schema_version", //$NON-NLS-1$
                    "Only LLM broker schema version 1 is supported"); //$NON-NLS-1$
            return;
        }
        try {
            if (hasModelOverride(payload)) {
                writeError(exchange, 422, "model_override_unsupported", //$NON-NLS-1$
                        "The active provider model is selected server-side"); //$NON-NLS-1$
                return;
            }
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, "invalid_request", e.getMessage()); //$NON-NLS-1$
            return;
        }

        final LlmRequest request;
        try {
            request = normalizeRequest(payload);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, "invalid_request", e.getMessage()); //$NON-NLS-1$
            return;
        }

        ILlmProvider provider = activeProvider();
        if (provider == null) {
            writeError(exchange, 503, "provider_unavailable", "No active LLM provider is available"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        Flight flight = new Flight(provider);
        if (!activeFlight.compareAndSet(null, flight)) {
            writeError(exchange, 409, "busy", "Another LLM request is already in flight"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        stream(exchange, request, flight);
    }

    /** Cancels the currently owned provider request, if any. */
    public void cancelActive() {
        Flight flight = activeFlight.get();
        if (flight != null) {
            cancelOwned(flight);
        }
    }

    private void stream(HttpExchange exchange, LlmRequest request, Flight flight) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", SSE_CONTENT_TYPE); //$NON-NLS-1$
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().set("Connection", "keep-alive"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            flight.output = output;
            startKeepalive(flight);
            try {
                flight.provider.streamComplete(request, chunk -> emitChunk(flight, chunk));
                if (!flight.terminal.get() && !flight.disconnected.get()) {
                    emit(flight, "done", object("finishReason", "stop")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    flight.terminal.set(true);
                }
            } catch (ClientDisconnectedException e) {
                // The cancellation path already owns provider cancellation.
            } catch (Exception e) {
                if (!flight.disconnected.get() && flight.terminal.compareAndSet(false, true)) {
                    LOG.warn("LLM broker provider request failed: %s", e.getClass().getSimpleName()); //$NON-NLS-1$
                    try {
                        emit(flight, "error", errorPayload("provider_error", "Provider request failed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    } catch (ClientDisconnectedException ignored) {
                        // Client left while the terminal error was being written.
                    }
                }
            }
        } catch (IOException e) {
            markDisconnected(flight);
        } finally {
            release(flight);
            exchange.close();
        }
    }

    private void emitChunk(Flight flight, LlmStreamChunk chunk) {
        if (chunk == null || flight.disconnected.get() || flight.terminal.get()) {
            return;
        }
        if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
            emit(flight, "delta", object("text", chunk.getContent())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (chunk.hasReasoningField()) {
            emit(flight, "reasoning", object("text", chunk.getReasoningContent())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (chunk.hasToolCalls()) {
            emit(flight, "tool_calls", toolCallsPayload(chunk.getToolCalls())); //$NON-NLS-1$
        }
        if (chunk.hasUsage()) {
            emit(flight, "usage", usagePayload(chunk.getUsage())); //$NON-NLS-1$
        }
        if (chunk.isError()) {
            if (flight.terminal.compareAndSet(false, true)) {
                emit(flight, "error", errorPayload("provider_error", "Provider request failed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return;
        }
        if (chunk.isComplete() && flight.terminal.compareAndSet(false, true)) {
            emit(flight, "done", object("finishReason", safe(chunk.getFinishReason(), "stop"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private void emit(Flight flight, String event, JsonObject data) {
        if (flight.disconnected.get()) {
            throw new ClientDisconnectedException();
        }
        data.addProperty("schemaVersion", SCHEMA_VERSION); //$NON-NLS-1$
        String frame = "event: " + event + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "data: " + gson.toJson(data) + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$
        try {
            synchronized (flight.writeLock) {
                flight.output.write(frame.getBytes(StandardCharsets.UTF_8));
                flight.output.flush();
            }
        } catch (IOException e) {
            markDisconnected(flight);
            throw new ClientDisconnectedException();
        }
    }

    private void startKeepalive(Flight flight) {
        Thread heartbeat = new Thread(() -> {
            try {
                while (true) {
                    synchronized (flight.lifecycleLock) {
                        if (flight.finished.get()) {
                            return;
                        }
                        flight.lifecycleLock.wait(keepaliveInterval.toMillis());
                        if (flight.finished.get()) {
                            return;
                        }
                    }
                    try {
                        synchronized (flight.writeLock) {
                            flight.output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
                            flight.output.flush();
                        }
                    } catch (IOException e) {
                        markDisconnected(flight);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "mcp-llm-sse-keepalive"); //$NON-NLS-1$
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    private void markDisconnected(Flight flight) {
        flight.disconnected.set(true);
        cancelOwned(flight);
    }

    private void cancelOwned(Flight flight) {
        synchronized (flight.lifecycleLock) {
            if (activeFlight.get() != flight || !flight.cancellationIssued.compareAndSet(false, true)) {
                return;
            }
            try {
                flight.provider.cancel();
            } catch (Exception e) {
                LOG.warn("Failed to cancel disconnected LLM provider request: %s", //$NON-NLS-1$
                        e.getClass().getSimpleName());
            }
        }
    }

    private void release(Flight flight) {
        synchronized (flight.lifecycleLock) {
            flight.finished.set(true);
            activeFlight.compareAndSet(flight, null);
            flight.lifecycleLock.notifyAll();
        }
    }

    private ILlmProvider activeProvider() {
        try {
            ILlmProvider provider = providerSupplier.get();
            return provider != null && provider.isConfigured() ? provider : null;
        } catch (Exception e) {
            LOG.warn("Failed to resolve active LLM provider: %s", e.getClass().getSimpleName()); //$NON-NLS-1$
            return null;
        }
    }

    private LlmRequest normalizeRequest(JsonObject payload) {
        JsonArray messagesJson = array(payload, "messages", true); //$NON-NLS-1$
        List<LlmMessage> messages = new ArrayList<>();
        for (JsonElement element : messagesJson) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each message must be an object"); //$NON-NLS-1$
            }
            messages.add(normalizeMessage(element.getAsJsonObject()));
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("At least one message is required"); //$NON-NLS-1$
        }

        LlmRequest.Builder builder = LlmRequest.builder().messages(messages).stream(true);
        JsonArray toolsJson = array(payload, "tools", false); //$NON-NLS-1$
        if (toolsJson != null) {
            for (JsonElement element : toolsJson) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("Each tool must be an object"); //$NON-NLS-1$
                }
                builder.addTool(normalizeTool(element.getAsJsonObject()));
            }
        }

        JsonObject options = object(payload, "options"); //$NON-NLS-1$
        if (options != null) {
            if (options.has("maxTokens") && !options.get("maxTokens").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                int maxTokens = integer(options, "maxTokens", -1); //$NON-NLS-1$
                if (maxTokens <= 0) {
                    throw new IllegalArgumentException("options.maxTokens must be positive"); //$NON-NLS-1$
                }
                builder.maxTokens(maxTokens);
            }
            if (options.has("temperature") && !options.get("temperature").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                double temperature = decimal(options, "temperature"); //$NON-NLS-1$
                if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
                    throw new IllegalArgumentException("options.temperature must be between 0 and 2"); //$NON-NLS-1$
                }
                builder.temperature(temperature);
            }
            String toolChoice = string(options, "toolChoice", null); //$NON-NLS-1$
            if (toolChoice != null && !toolChoice.isBlank()) {
                try {
                    builder.toolChoice(LlmRequest.ToolChoice.valueOf(toolChoice.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("options.toolChoice must be auto, required, or none"); //$NON-NLS-1$
                }
            }
        }
        return builder.build();
    }

    private LlmMessage normalizeMessage(JsonObject value) {
        String roleValue = string(value, "role", null); //$NON-NLS-1$
        if (roleValue == null) {
            throw new IllegalArgumentException("Message role is required"); //$NON-NLS-1$
        }
        final LlmMessage.Role role;
        try {
            role = LlmMessage.Role.valueOf(roleValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported message role: " + roleValue); //$NON-NLS-1$
        }
        String content = string(value, "content", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String reasoning = string(value, "reasoning", string(value, "reasoningContent", null)); //$NON-NLS-1$ //$NON-NLS-2$
        String toolCallId = string(value, "toolCallId", null); //$NON-NLS-1$
        List<ToolCall> calls = normalizeToolCalls(array(value, "toolCalls", false)); //$NON-NLS-1$
        return new LlmMessage(role, content, reasoning, null, calls, toolCallId);
    }

    private ToolDefinition normalizeTool(JsonObject value) {
        String name = requiredString(value, "name", "Tool name is required"); //$NON-NLS-1$ //$NON-NLS-2$
        String description = string(value, "description", ""); //$NON-NLS-1$ //$NON-NLS-2$
        JsonElement schema = first(value, "inputSchema", "parameters", "parametersSchema"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String parametersSchema;
        if (schema == null || schema.isJsonNull()) {
            parametersSchema = "{}"; //$NON-NLS-1$
        } else if (schema.isJsonPrimitive() && schema.getAsJsonPrimitive().isString()) {
            parametersSchema = schema.getAsString();
            try {
                JsonParser.parseString(parametersSchema);
            } catch (JsonSyntaxException e) {
                throw new IllegalArgumentException("Tool parametersSchema must contain valid JSON"); //$NON-NLS-1$
            }
        } else {
            parametersSchema = gson.toJson(schema);
        }
        return new ToolDefinition(name, description, parametersSchema);
    }

    private List<ToolCall> normalizeToolCalls(JsonArray values) {
        if (values == null) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each tool call must be an object"); //$NON-NLS-1$
            }
            JsonObject value = element.getAsJsonObject();
            String id = requiredString(value, "id", "Tool call id is required"); //$NON-NLS-1$ //$NON-NLS-2$
            String name = requiredString(value, "name", "Tool call name is required"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonElement arguments = value.get("arguments"); //$NON-NLS-1$
            String argumentsJson = arguments == null || arguments.isJsonNull()
                    ? "{}" //$NON-NLS-1$
                    : arguments.isJsonPrimitive() && arguments.getAsJsonPrimitive().isString()
                        ? arguments.getAsString() : gson.toJson(arguments);
            calls.add(new ToolCall(id, name, argumentsJson));
        }
        return calls;
    }

    private boolean hasModelOverride(JsonObject payload) {
        if (nonBlank(payload.get("model"))) { //$NON-NLS-1$
            return true;
        }
        JsonObject options = object(payload, "options"); //$NON-NLS-1$
        return options != null && nonBlank(options.get("model")); //$NON-NLS-1$
    }

    private boolean nonBlank(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        return !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !value.getAsString().isBlank();
    }

    private JsonObject toolCallsPayload(List<ToolCall> calls) {
        JsonArray values = new JsonArray();
        for (ToolCall call : calls) {
            JsonObject value = new JsonObject();
            value.addProperty("id", call.getId()); //$NON-NLS-1$
            value.addProperty("name", call.getName()); //$NON-NLS-1$
            try {
                value.add("arguments", JsonParser.parseString(call.getArguments())); //$NON-NLS-1$
            } catch (JsonSyntaxException e) {
                value.addProperty("arguments", call.getArguments()); //$NON-NLS-1$
            }
            value.addProperty("argumentsRepaired", call.isArgumentsRepaired()); //$NON-NLS-1$
            values.add(value);
        }
        JsonObject payload = new JsonObject();
        payload.add("toolCalls", values); //$NON-NLS-1$
        return payload;
    }

    private JsonObject usagePayload(LlmResponse.Usage usage) {
        JsonObject payload = new JsonObject();
        payload.addProperty("promptTokens", usage.getPromptTokens()); //$NON-NLS-1$
        payload.addProperty("completionTokens", usage.getCompletionTokens()); //$NON-NLS-1$
        payload.addProperty("totalTokens", usage.getTotalTokens()); //$NON-NLS-1$
        payload.addProperty("cacheReadInputTokens", usage.getCacheReadInputTokens()); //$NON-NLS-1$
        payload.addProperty("cacheCreationInputTokens", usage.getCacheCreationInputTokens()); //$NON-NLS-1$
        return payload;
    }

    private JsonObject errorPayload(String code, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code); //$NON-NLS-1$
        payload.addProperty("message", message); //$NON-NLS-1$
        return payload;
    }

    private JsonObject object(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private void writeError(HttpExchange exchange, int status, String code, String message) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("error", code); //$NON-NLS-1$
        payload.addProperty("message", message); //$NON-NLS-1$
        writeJson(exchange, status, payload);
    }

    private void writeJson(HttpExchange exchange, int status, JsonObject payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static JsonArray array(JsonObject object, String key, boolean required) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) {
                throw new IllegalArgumentException(key + " is required"); //$NON-NLS-1$
            }
            return null;
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array"); //$NON-NLS-1$
        }
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object"); //$NON-NLS-1$
        }
        return value.getAsJsonObject();
    }

    private static JsonElement first(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                return object.get(key);
            }
        }
        return null;
    }

    private static String requiredString(JsonObject object, String key, String message) {
        String value = string(object, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string"); //$NON-NLS-1$
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new NumberFormatException();
            }
            return value.getAsBigDecimal().intValueExact();
        } catch (Exception e) {
            throw new IllegalArgumentException(key + " must be an integer"); //$NON-NLS-1$
        }
    }

    private static double decimal(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new NumberFormatException();
            }
            return value.getAsDouble();
        } catch (Exception e) {
            throw new IllegalArgumentException(key + " must be a number"); //$NON-NLS-1$
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class Flight {
        private final ILlmProvider provider;
        private final Object writeLock = new Object();
        private final Object lifecycleLock = new Object();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final AtomicBoolean cancellationIssued = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile OutputStream output;

        Flight(ILlmProvider provider) {
            this.provider = provider;
        }
    }

    private static final class ClientDisconnectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
