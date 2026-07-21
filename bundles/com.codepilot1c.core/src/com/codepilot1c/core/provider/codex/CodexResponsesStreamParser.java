/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses an OpenAI Responses API SSE stream (Codex backend) and emits {@link LlmStreamChunk}s.
 *
 * <p>Consumes {@code data:} lines, switching on the event {@code type}. Text and reasoning deltas
 * are forwarded immediately; function-call items are accumulated by {@code item_id} and emitted as
 * a single tool-call chunk on {@code response.completed}.</p>
 */
public class CodexResponsesStreamParser {

    private final Consumer<LlmStreamChunk> consumer;
    /** Accumulating function calls keyed by streaming item id (insertion-ordered). */
    private final Map<String, FunctionCallBuilder> functionCalls = new LinkedHashMap<>();
    private LlmResponse.Usage usage;
    private boolean completed;
    private boolean failed;

    public CodexResponsesStreamParser(Consumer<LlmStreamChunk> consumer) {
        this.consumer = consumer;
    }

    /**
     * Processes one raw SSE line. Calls {@code complete} once the stream reaches a terminal event.
     *
     * @param line     the raw line
     * @param complete callback to stop further reading
     */
    public void process(String line, Runnable complete) {
        if (line == null || line.isEmpty() || !line.startsWith("data:")) { //$NON-NLS-1$
            return;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) { //$NON-NLS-1$
            return;
        }
        JsonObject event;
        try {
            JsonElement parsed = JsonParser.parseString(data);
            if (!parsed.isJsonObject()) {
                return;
            }
            event = parsed.getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        String type = getString(event, "type"); //$NON-NLS-1$
        if (type == null) {
            return;
        }
        switch (type) {
            case "response.output_text.delta": //$NON-NLS-1$
                emitText(getString(event, "delta")); //$NON-NLS-1$
                break;
            case "response.reasoning_summary_text.delta": //$NON-NLS-1$
            case "response.reasoning_text.delta": //$NON-NLS-1$
                emitReasoning(getString(event, "delta")); //$NON-NLS-1$
                break;
            case "response.output_item.added": //$NON-NLS-1$
                onItemAdded(event);
                break;
            case "response.function_call_arguments.delta": //$NON-NLS-1$
                onArgumentsDelta(event);
                break;
            case "response.function_call_arguments.done": //$NON-NLS-1$
                onArgumentsDone(event);
                break;
            case "response.completed": //$NON-NLS-1$
            case "response.done": //$NON-NLS-1$
            case "response.incomplete": //$NON-NLS-1$
                onCompleted(event);
                complete.run();
                break;
            case "response.failed": //$NON-NLS-1$
                onFailed(event);
                complete.run();
                break;
            default:
                break;
        }
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    private void emitText(String delta) {
        if (delta != null && !delta.isEmpty()) {
            consumer.accept(LlmStreamChunk.content(delta));
        }
    }

    private void emitReasoning(String delta) {
        if (delta != null && !delta.isEmpty()) {
            consumer.accept(LlmStreamChunk.reasoning(delta));
        }
    }

    private void onItemAdded(JsonObject event) {
        JsonObject item = getObject(event, "item"); //$NON-NLS-1$
        if (item == null || !"function_call".equals(getString(item, "type"))) { //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String itemId = getString(item, "id"); //$NON-NLS-1$
        String callId = getString(item, "call_id"); //$NON-NLS-1$
        if (itemId == null) {
            itemId = callId;
        }
        if (itemId == null) {
            return;
        }
        FunctionCallBuilder builder = new FunctionCallBuilder();
        builder.callId = callId != null ? callId : itemId;
        builder.name = getString(item, "name"); //$NON-NLS-1$
        String initialArguments = getString(item, "arguments"); //$NON-NLS-1$
        if (initialArguments != null) {
            builder.arguments.append(initialArguments);
        }
        functionCalls.put(itemId, builder);
    }

    private void onArgumentsDelta(JsonObject event) {
        String itemId = getString(event, "item_id"); //$NON-NLS-1$
        String delta = getString(event, "delta"); //$NON-NLS-1$
        if (itemId == null || delta == null) {
            return;
        }
        FunctionCallBuilder builder = functionCalls.get(itemId);
        if (builder != null) {
            builder.arguments.append(delta);
        }
    }

    private void onArgumentsDone(JsonObject event) {
        String itemId = getString(event, "item_id"); //$NON-NLS-1$
        String arguments = getString(event, "arguments"); //$NON-NLS-1$
        if (itemId == null || arguments == null) {
            return;
        }
        FunctionCallBuilder builder = functionCalls.get(itemId);
        if (builder != null) {
            builder.arguments.setLength(0);
            builder.arguments.append(arguments);
        }
    }

    private void onCompleted(JsonObject event) {
        JsonObject response = getObject(event, "response"); //$NON-NLS-1$
        if (response != null) {
            JsonObject usageJson = getObject(response, "usage"); //$NON-NLS-1$
            if (usageJson != null) {
                usage = parseUsage(usageJson);
            }
        }
        List<ToolCall> toolCalls = buildToolCalls();
        if (!toolCalls.isEmpty()) {
            consumer.accept(LlmStreamChunk.toolCalls(toolCalls));
        }
        if (usage != null) {
            LlmStreamChunk usageChunk = LlmStreamChunk.usage(usage);
            if (usageChunk != null) {
                consumer.accept(usageChunk);
            }
        }
        String finishReason = toolCalls.isEmpty()
            ? LlmResponse.FINISH_REASON_STOP
            : LlmResponse.FINISH_REASON_TOOL_USE;
        consumer.accept(LlmStreamChunk.complete(finishReason));
        completed = true;
    }

    private void onFailed(JsonObject event) {
        String message = "OpenAI Codex response failed"; //$NON-NLS-1$
        JsonObject response = getObject(event, "response"); //$NON-NLS-1$
        if (response != null) {
            JsonObject error = getObject(response, "error"); //$NON-NLS-1$
            if (error != null) {
                String detail = getString(error, "message"); //$NON-NLS-1$
                if (detail != null && !detail.isBlank()) {
                    message = detail;
                }
            }
        }
        consumer.accept(LlmStreamChunk.error(message));
        failed = true;
    }

    private List<ToolCall> buildToolCalls() {
        List<ToolCall> result = new ArrayList<>();
        for (FunctionCallBuilder builder : functionCalls.values()) {
            if (builder.callId != null && builder.name != null && !builder.name.isBlank()) {
                String arguments = builder.arguments.length() > 0 ? builder.arguments.toString() : "{}"; //$NON-NLS-1$
                result.add(new ToolCall(builder.callId, builder.name, arguments));
            }
        }
        return result;
    }

    private static LlmResponse.Usage parseUsage(JsonObject usageJson) {
        int inputTokens = getInt(usageJson, "input_tokens", 0); //$NON-NLS-1$
        int outputTokens = getInt(usageJson, "output_tokens", 0); //$NON-NLS-1$
        int totalTokens = getInt(usageJson, "total_tokens", inputTokens + outputTokens); //$NON-NLS-1$
        int cachedTokens = 0;
        JsonObject details = getObject(usageJson, "input_tokens_details"); //$NON-NLS-1$
        if (details != null) {
            cachedTokens = getInt(details, "cached_tokens", 0); //$NON-NLS-1$
        }
        return new LlmResponse.Usage(inputTokens, cachedTokens, outputTokens, totalTokens, cachedTokens, 0);
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static JsonObject getObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    private static int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Mutable accumulator for a streaming function call. */
    private static final class FunctionCallBuilder {
        private String callId;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
