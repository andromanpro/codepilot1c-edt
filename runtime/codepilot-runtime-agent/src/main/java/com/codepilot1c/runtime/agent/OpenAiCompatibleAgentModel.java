/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import com.codepilot1c.runtime.provider.ChatCompletionResponse;
import com.codepilot1c.runtime.provider.OpenAiCompatibleProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** OpenAI-compatible wire adapter for the provider-neutral {@link AgentModel}. */
public final class OpenAiCompatibleAgentModel implements AgentModel {
    private final OpenAiCompatibleProvider provider;

    public OpenAiCompatibleAgentModel(OpenAiCompatibleProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider"); //$NON-NLS-1$
    }

    @Override
    public CompletionStage<AgentMessage.Assistant> complete(Request request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        Objects.requireNonNull(cancellation, "cancellation"); //$NON-NLS-1$
        if (cancellation.isCancelled()) return cancelled();

        CompletableFuture<ChatCompletionResponse> response;
        try {
            response = provider.completeRaw(serialize(request));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new AgentModelException(
                    AgentModelException.Kind.TRANSPORT, "Provider request could not be started", failure)); //$NON-NLS-1$
        }
        CancellationToken.Registration registration = cancellation.onCancel(() -> response.cancel(true));
        return response.handle((value, failure) -> {
            registration.close();
            if (failure != null) {
                if (cancellation.isCancelled()) throw new CancellationException();
                throw new CompletionException(new AgentModelException(
                        AgentModelException.Kind.TRANSPORT, "Provider transport failed", unwrap(failure))); //$NON-NLS-1$
            }
            if (!value.isSuccessful()) {
                throw new CompletionException(new AgentModelException(
                        AgentModelException.Kind.HTTP,
                        "Provider returned HTTP status " + value.statusCode())); //$NON-NLS-1$
            }
            return parse(value.body());
        });
    }

    private JsonObject serialize(Request request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", provider.configuration().defaultModel()); //$NON-NLS-1$
        JsonArray messages = new JsonArray();
        for (AgentMessage message : request.messages()) messages.add(serializeMessage(message));
        root.add("messages", messages); //$NON-NLS-1$
        if (!request.tools().isEmpty()) {
            JsonArray definitions = new JsonArray();
            for (ToolDefinition tool : request.tools()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name()); //$NON-NLS-1$
                function.addProperty("description", tool.description()); //$NON-NLS-1$
                function.add("parameters", tool.inputSchema()); //$NON-NLS-1$
                JsonObject definition = new JsonObject();
                definition.addProperty("type", "function"); //$NON-NLS-1$ //$NON-NLS-2$
                definition.add("function", function); //$NON-NLS-1$
                definitions.add(definition);
            }
            root.add("tools", definitions); //$NON-NLS-1$
        }
        return root;
    }

    private JsonObject serializeMessage(AgentMessage message) {
        JsonObject value = new JsonObject();
        if (message instanceof AgentMessage.Text text) {
            value.addProperty("role", text.role().name().toLowerCase(java.util.Locale.ROOT)); //$NON-NLS-1$
            value.addProperty("content", text.content()); //$NON-NLS-1$
        } else if (message instanceof AgentMessage.Assistant assistant) {
            value.addProperty("role", "assistant"); //$NON-NLS-1$ //$NON-NLS-2$
            if (assistant.text().isPresent()) value.addProperty("content", assistant.text().get()); //$NON-NLS-1$
            else value.add("content", JsonNull.INSTANCE); //$NON-NLS-1$
            if (!assistant.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ToolCall call : assistant.toolCalls()) calls.add(serializeCall(call));
                value.add("tool_calls", calls); //$NON-NLS-1$
            }
        } else if (message instanceof AgentMessage.Tool tool) {
            value.addProperty("role", "tool"); //$NON-NLS-1$ //$NON-NLS-2$
            value.addProperty("tool_call_id", tool.callId()); //$NON-NLS-1$
            value.addProperty("name", tool.toolName()); //$NON-NLS-1$
            value.addProperty("content", tool.result().toJson().toString()); //$NON-NLS-1$
        } else {
            throw new IllegalArgumentException("Unsupported agent message type"); //$NON-NLS-1$
        }
        return value;
    }

    private JsonObject serializeCall(ToolCall call) {
        JsonObject function = new JsonObject();
        function.addProperty("name", call.name()); //$NON-NLS-1$
        function.addProperty("arguments", call.argumentsJson()); //$NON-NLS-1$
        JsonObject value = new JsonObject();
        value.addProperty("id", call.id()); //$NON-NLS-1$
        value.addProperty("type", "function"); //$NON-NLS-1$ //$NON-NLS-2$
        value.add("function", function); //$NON-NLS-1$
        return value;
    }

    private AgentMessage.Assistant parse(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw malformed();
            JsonArray choices = array(parsed.getAsJsonObject(), "choices"); //$NON-NLS-1$
            if (choices.size() == 0 || !choices.get(0).isJsonObject()) throw malformed();
            JsonObject message = object(choices.get(0).getAsJsonObject(), "message"); //$NON-NLS-1$
            Optional<String> text = optionalString(message, "content"); //$NON-NLS-1$
            List<ToolCall> calls = parseCalls(message);
            return new AgentMessage.Assistant(text, calls);
        } catch (AgentModelException failure) {
            throw failure;
        } catch (JsonParseException | IllegalArgumentException
                | IllegalStateException | UnsupportedOperationException failure) {
            throw malformed(failure);
        }
    }

    private List<ToolCall> parseCalls(JsonObject message) {
        JsonElement raw = message.get("tool_calls"); //$NON-NLS-1$
        if (raw == null || raw.isJsonNull()) return List.of();
        if (!raw.isJsonArray()) throw malformed();
        List<ToolCall> calls = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonObject()) throw malformed();
            JsonObject call = element.getAsJsonObject();
            String id = requiredString(call, "id"); //$NON-NLS-1$
            if (!ids.add(id)) throw malformed();
            JsonObject function = object(call, "function"); //$NON-NLS-1$
            calls.add(new ToolCall(id, requiredString(function, "name"), //$NON-NLS-1$
                    requiredString(function, "arguments"))); //$NON-NLS-1$
        }
        return List.copyOf(calls);
    }

    private JsonArray array(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) throw malformed();
        return value.getAsJsonArray();
    }

    private JsonObject object(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw malformed();
        return value.getAsJsonObject();
    }

    private Optional<String> optionalString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw malformed();
        return Optional.of(value.getAsString()).filter(text -> !text.isEmpty());
    }

    private String requiredString(JsonObject object, String field) {
        return optionalString(object, field).orElseThrow(this::malformed);
    }

    private AgentModelException malformed() {
        return new AgentModelException(AgentModelException.Kind.MALFORMED_RESPONSE,
                "Provider response does not match the chat completion contract"); //$NON-NLS-1$
    }

    private AgentModelException malformed(Throwable cause) {
        return new AgentModelException(AgentModelException.Kind.MALFORMED_RESPONSE,
                "Provider response does not match the chat completion contract", cause); //$NON-NLS-1$
    }

    private Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while (value instanceof CompletionException && value.getCause() != null) value = value.getCause();
        return value;
    }

    private CompletionStage<AgentMessage.Assistant> cancelled() {
        CompletableFuture<AgentMessage.Assistant> future = new CompletableFuture<>();
        future.cancel(false);
        return future;
    }
}
