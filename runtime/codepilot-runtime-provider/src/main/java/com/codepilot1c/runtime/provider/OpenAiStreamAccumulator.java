/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Converts OpenAI-compatible JSON chunks into provider-neutral events. */
final class OpenAiStreamAccumulator {

    private final Consumer<ProviderStreamEvent> consumer;
    private final Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
    private boolean toolCallsEmitted;
    private boolean done;

    OpenAiStreamAccumulator(Consumer<ProviderStreamEvent> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer"); //$NON-NLS-1$
    }

    boolean isDone() {
        return done;
    }

    void accept(SseEventParser.Event event) {
        if (done) return;
        if ("error".equals(event.type())) { //$NON-NLS-1$
            throw malformed("Provider sent an SSE error event"); //$NON-NLS-1$
        }
        if ("[DONE]".equals(event.data().trim())) { //$NON-NLS-1$
            emitToolCalls();
            done = true;
            consumer.accept(new ProviderStreamEvent.Done());
            return;
        }
        JsonObject chunk = parseChunk(event.data());
        if (chunk.has("error")) throw malformed("Provider sent an error chunk"); //$NON-NLS-1$ //$NON-NLS-2$

        boolean recognized = false;
        if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            emitUsage(object(chunk, "usage")); //$NON-NLS-1$
            recognized = true;
        }
        if (chunk.has("choices")) { //$NON-NLS-1$
            JsonArray choices = array(chunk, "choices"); //$NON-NLS-1$
            recognized = true;
            if (!choices.isEmpty()) acceptChoice(firstChoice(choices));
        }
        if (!recognized) throw malformed("Streaming chunk has no choices or usage"); //$NON-NLS-1$
    }

    private JsonObject parseChunk(String data) {
        try {
            JsonElement parsed = JsonParser.parseString(data);
            if (!parsed.isJsonObject()) throw malformed("Streaming chunk is not a JSON object"); //$NON-NLS-1$
            return parsed.getAsJsonObject();
        } catch (ProviderStreamException failure) {
            throw failure;
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException failure) {
            throw malformed("Streaming chunk contains malformed JSON"); //$NON-NLS-1$
        }
    }

    private JsonObject firstChoice(JsonArray choices) {
        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) throw malformed("Streaming choice is not an object"); //$NON-NLS-1$
        JsonObject choice = first.getAsJsonObject();
        if (choice.has("index") && integer(choice, "index") != 0) { //$NON-NLS-1$ //$NON-NLS-2$
            throw malformed("Streaming response does not contain choice zero"); //$NON-NLS-1$
        }
        return choice;
    }

    private void acceptChoice(JsonObject choice) {
        JsonElement rawDelta = choice.get("delta"); //$NON-NLS-1$
        if (rawDelta != null && !rawDelta.isJsonNull()) {
            if (!rawDelta.isJsonObject()) throw malformed("Streaming delta is not an object"); //$NON-NLS-1$
            acceptDelta(rawDelta.getAsJsonObject());
        }
        JsonElement finishReason = choice.get("finish_reason"); //$NON-NLS-1$
        if (finishReason != null && !finishReason.isJsonNull()) {
            if (!finishReason.isJsonPrimitive() || !finishReason.getAsJsonPrimitive().isString()) {
                throw malformed("Streaming finish reason is not text"); //$NON-NLS-1$
            }
            emitToolCalls();
        }
    }

    private void acceptDelta(JsonObject delta) {
        emitString(delta, "content", ProviderStreamEvent.TextDelta::new); //$NON-NLS-1$
        if (delta.has("reasoning_content")) { //$NON-NLS-1$
            emitString(delta, "reasoning_content", ProviderStreamEvent.ReasoningDelta::new); //$NON-NLS-1$
        } else {
            emitString(delta, "reasoning", ProviderStreamEvent.ReasoningDelta::new); //$NON-NLS-1$
        }
        JsonElement rawCalls = delta.get("tool_calls"); //$NON-NLS-1$
        if (rawCalls == null || rawCalls.isJsonNull()) return;
        if (toolCallsEmitted) throw malformed("Tool-call fragments arrived after completion"); //$NON-NLS-1$
        if (!rawCalls.isJsonArray()) throw malformed("Streaming tool calls are not an array"); //$NON-NLS-1$
        for (JsonElement rawCall : rawCalls.getAsJsonArray()) acceptToolCall(rawCall);
    }

    private void emitString(JsonObject object, String field,
            java.util.function.Function<String, ProviderStreamEvent> eventFactory) {
        JsonElement raw = object.get(field);
        if (raw == null || raw.isJsonNull()) return;
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            throw malformed("Streaming delta field is not text"); //$NON-NLS-1$
        }
        String value = raw.getAsString();
        if (!value.isEmpty()) consumer.accept(eventFactory.apply(value));
    }

    private void acceptToolCall(JsonElement rawCall) {
        if (!rawCall.isJsonObject()) throw malformed("Tool-call fragment is not an object"); //$NON-NLS-1$
        JsonObject call = rawCall.getAsJsonObject();
        int index = integer(call, "index"); //$NON-NLS-1$
        if (index < 0) throw malformed("Tool-call index is negative"); //$NON-NLS-1$
        JsonElement rawType = call.get("type"); //$NON-NLS-1$
        if (rawType != null && !rawType.isJsonNull()
                && (!rawType.isJsonPrimitive() || !rawType.getAsJsonPrimitive().isString()
                        || !"function".equals(rawType.getAsString()))) { //$NON-NLS-1$
            throw malformed("Tool-call type is not function"); //$NON-NLS-1$
        }
        ToolCallBuilder builder = toolCalls.computeIfAbsent(index, ToolCallBuilder::new);
        appendOptionalString(call, "id", builder.id); //$NON-NLS-1$
        JsonElement rawFunction = call.get("function"); //$NON-NLS-1$
        if (rawFunction != null && !rawFunction.isJsonNull()) {
            if (!rawFunction.isJsonObject()) throw malformed("Tool-call function is not an object"); //$NON-NLS-1$
            JsonObject function = rawFunction.getAsJsonObject();
            appendOptionalString(function, "name", builder.name); //$NON-NLS-1$
            appendOptionalString(function, "arguments", builder.arguments); //$NON-NLS-1$
        }
    }

    private void appendOptionalString(JsonObject object, String field, StringBuilder target) {
        JsonElement raw = object.get(field);
        if (raw == null || raw.isJsonNull()) return;
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            throw malformed("Tool-call fragment field is not text"); //$NON-NLS-1$
        }
        target.append(raw.getAsString());
    }

    private void emitToolCalls() {
        if (toolCallsEmitted) return;
        for (ToolCallBuilder call : toolCalls.values()) {
            if (call.id.length() == 0 || call.name.length() == 0) {
                throw malformed("Tool call is missing an id or function name"); //$NON-NLS-1$
            }
            validateArguments(call.arguments.toString());
            consumer.accept(new ProviderStreamEvent.ToolCall(call.index,
                    call.id.toString(), call.name.toString(), call.arguments.toString()));
        }
        toolCallsEmitted = true;
    }

    private void validateArguments(String arguments) {
        try {
            if (!JsonParser.parseString(arguments).isJsonObject()) {
                throw malformed("Tool-call arguments are not a JSON object"); //$NON-NLS-1$
            }
        } catch (ProviderStreamException failure) {
            throw failure;
        } catch (JsonParseException | IllegalStateException failure) {
            throw malformed("Tool-call arguments contain malformed JSON"); //$NON-NLS-1$
        }
    }

    private void emitUsage(JsonObject usage) {
        consumer.accept(new ProviderStreamEvent.Usage(
                nonNegativeLong(usage, "prompt_tokens"), //$NON-NLS-1$
                nonNegativeLong(usage, "completion_tokens"), //$NON-NLS-1$
                nonNegativeLong(usage, "total_tokens"))); //$NON-NLS-1$
    }

    private long nonNegativeLong(JsonObject object, String field) {
        JsonElement raw = object.get(field);
        if (raw == null || !raw.isJsonPrimitive()) throw malformed("Usage token count is missing"); //$NON-NLS-1$
        JsonPrimitive primitive = raw.getAsJsonPrimitive();
        if (!primitive.isNumber()) throw malformed("Usage token count is not numeric"); //$NON-NLS-1$
        try {
            String encoded = primitive.getAsString();
            if (!encoded.matches("[0-9]+")) { //$NON-NLS-1$
                throw malformed("Usage token count is invalid"); //$NON-NLS-1$
            }
            return Long.parseLong(encoded);
        } catch (NumberFormatException failure) {
            throw malformed("Usage token count is invalid"); //$NON-NLS-1$
        }
    }

    private int integer(JsonObject object, String field) {
        JsonElement raw = object.get(field);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            throw malformed("Streaming index is missing or invalid"); //$NON-NLS-1$
        }
        try {
            String value = raw.getAsString();
            if (!value.matches("-?[0-9]+")) throw malformed("Streaming index is not an integer"); //$NON-NLS-1$
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw malformed("Streaming index is out of range"); //$NON-NLS-1$
        }
    }

    private JsonObject object(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonObject()) throw malformed("Streaming object field is invalid"); //$NON-NLS-1$
        return value.getAsJsonObject();
    }

    private JsonArray array(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonArray()) throw malformed("Streaming array field is invalid"); //$NON-NLS-1$
        return value.getAsJsonArray();
    }

    private ProviderStreamException malformed(String message) {
        return new ProviderStreamException(ProviderStreamException.Kind.RESPONSE, message);
    }

    private static final class ToolCallBuilder {
        private final int index;
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallBuilder(int index) {
            this.index = index;
        }
    }
}
