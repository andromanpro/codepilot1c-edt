/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.config.ProviderMessageContentSerializer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Builds an OpenAI Responses API request body for the Codex backend.
 *
 * <p>Maps the internal conversation model onto Responses {@code input} items:</p>
 * <ul>
 *   <li>{@code SYSTEM} → top-level {@code instructions}</li>
 *   <li>{@code USER} → {@code {type:message, role:user, content:[{type:input_text}]}}</li>
 *   <li>{@code ASSISTANT} text → {@code {type:message, role:assistant, content:[{type:output_text}]}}</li>
 *   <li>{@code ASSISTANT} tool calls → one {@code {type:function_call, call_id, name, arguments}} each</li>
 *   <li>{@code TOOL} result → {@code {type:function_call_output, call_id, output}}</li>
 * </ul>
 */
public class CodexResponsesRequestBuilder {

    private final Gson gson = new Gson();

    /**
     * Builds the Responses API request body.
     *
     * @param request          the LLM request
     * @param defaultModel     model id to use when the request omits one
     * @param defaultMaxTokens output-token cap to use when the request omits one
     * @param stream           whether to request a streamed response
     * @return the serialized JSON body
     */
    public String build(LlmRequest request, String defaultModel, int defaultMaxTokens, boolean stream) {
        return build(request, defaultModel, defaultMaxTokens, stream, CodexOAuthConstants.DEFAULT_REASONING_EFFORT);
    }

    /**
     * Builds a Responses API request with an explicit reasoning effort.
     */
    public String build(LlmRequest request, String defaultModel, int defaultMaxTokens, boolean stream,
            String reasoningEffort) {
        JsonObject body = new JsonObject();
        body.addProperty("model", resolveModel(request, defaultModel)); //$NON-NLS-1$
        // The Codex backend requires store=false; text/include mirror the official Codex client.
        body.addProperty("store", Boolean.FALSE); //$NON-NLS-1$
        JsonObject text = new JsonObject();
        text.addProperty("verbosity", "medium"); //$NON-NLS-1$ //$NON-NLS-2$
        body.add("text", text); //$NON-NLS-1$
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", normalizeReasoningEffort(reasoningEffort)); //$NON-NLS-1$
        body.add("reasoning", reasoning); //$NON-NLS-1$
        JsonArray include = new JsonArray();
        include.add("reasoning.encrypted_content"); //$NON-NLS-1$
        body.add("include", include); //$NON-NLS-1$

        StringBuilder instructions = new StringBuilder();
        JsonArray input = new JsonArray();
        for (LlmMessage msg : request.getMessages()) {
            switch (msg.getRole()) {
                case SYSTEM:
                    if (instructions.length() > 0) {
                        instructions.append("\n\n"); //$NON-NLS-1$
                    }
                    instructions.append(text(msg));
                    break;
                case USER:
                    input.add(userMessageItem(msg));
                    break;
                case ASSISTANT:
                    String assistantText = text(msg);
                    if (assistantText != null && !assistantText.isEmpty()) {
                        input.add(messageItem("assistant", "output_text", assistantText)); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (msg.hasToolCalls()) {
                        for (ToolCall call : msg.getToolCalls()) {
                            input.add(functionCallItem(call));
                        }
                    }
                    break;
                case TOOL:
                    input.add(functionCallOutputItem(msg.getToolCallId(), text(msg)));
                    break;
                default:
                    break;
            }
        }

        if (instructions.length() > 0) {
            body.addProperty("instructions", instructions.toString()); //$NON-NLS-1$
        }
        body.add("input", input); //$NON-NLS-1$

        if (request.hasTools()) {
            JsonArray tools = new JsonArray();
            for (ToolDefinition tool : request.getTools()) {
                tools.add(toolItem(tool));
            }
            body.add("tools", tools); //$NON-NLS-1$
            body.addProperty("tool_choice", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
            body.addProperty("parallel_tool_calls", Boolean.TRUE); //$NON-NLS-1$
        }

        body.addProperty("stream", Boolean.valueOf(stream)); //$NON-NLS-1$

        return gson.toJson(body);
    }

    private String normalizeReasoningEffort(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            for (String supported : CodexOAuthConstants.REASONING_EFFORTS) {
                if (supported.equals(normalized)) {
                    return supported;
                }
            }
        }
        return CodexOAuthConstants.DEFAULT_REASONING_EFFORT;
    }

    /**
     * Builds a Responses {@code user} message, serializing image attachments as
     * {@code input_image} parts (data URI) so multimodal models actually receive the
     * picture instead of a {@code [Image: ...]} text placeholder. Falls back to text
     * for non-image parts and for images that cannot be read.
     */
    private JsonObject userMessageItem(LlmMessage msg) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "message"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("role", "user"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray content = new JsonArray();
        if (msg.hasContentParts()) {
            for (LlmContentPart part : msg.getContentParts()) {
                if (part.isImage()) {
                    String dataUri = ProviderMessageContentSerializer.toImageDataUri(part.getAttachment());
                    if (dataUri != null) {
                        JsonObject imagePart = new JsonObject();
                        imagePart.addProperty("type", "input_image"); //$NON-NLS-1$ //$NON-NLS-2$
                        imagePart.addProperty("image_url", dataUri); //$NON-NLS-1$
                        imagePart.addProperty("detail", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
                        content.add(imagePart);
                        continue;
                    }
                    addInputText(content, part.toTextFallback());
                } else if (part.isText()) {
                    addInputText(content, part.getText());
                } else {
                    addInputText(content, part.toTextFallback());
                }
            }
        }
        if (content.size() == 0) {
            addInputText(content, text(msg));
        }
        item.add("content", content); //$NON-NLS-1$
        return item;
    }

    private void addInputText(JsonArray content, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        JsonObject part = new JsonObject();
        part.addProperty("type", "input_text"); //$NON-NLS-1$ //$NON-NLS-2$
        part.addProperty("text", value); //$NON-NLS-1$
        content.add(part);
    }

    private JsonObject messageItem(String role, String partType, String text) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "message"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("role", role); //$NON-NLS-1$
        JsonObject part = new JsonObject();
        part.addProperty("type", partType); //$NON-NLS-1$
        part.addProperty("text", text != null ? text : ""); //$NON-NLS-1$ //$NON-NLS-2$
        if ("output_text".equals(partType)) { //$NON-NLS-1$
            part.add("annotations", new JsonArray()); //$NON-NLS-1$
        }
        JsonArray content = new JsonArray();
        content.add(part);
        item.add("content", content); //$NON-NLS-1$
        return item;
    }

    private JsonObject functionCallItem(ToolCall call) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("call_id", call.getId()); //$NON-NLS-1$
        item.addProperty("name", call.getName()); //$NON-NLS-1$
        item.addProperty("arguments", call.getArguments()); //$NON-NLS-1$
        return item;
    }

    private JsonObject functionCallOutputItem(String callId, String output) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("call_id", callId); //$NON-NLS-1$
        item.addProperty("output", output != null ? output : ""); //$NON-NLS-1$ //$NON-NLS-2$
        return item;
    }

    private JsonObject toolItem(ToolDefinition tool) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("name", tool.getName()); //$NON-NLS-1$
        item.addProperty("description", tool.getDescription()); //$NON-NLS-1$
        try {
            item.add("parameters", JsonParser.parseString(tool.getParametersSchema())); //$NON-NLS-1$
        } catch (Exception e) {
            JsonObject empty = new JsonObject();
            empty.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
            item.add("parameters", empty); //$NON-NLS-1$
        }
        return item;
    }

    private static String text(LlmMessage msg) {
        return msg.hasContentParts() ? msg.getTextualContentFallback() : msg.getContent();
    }

    private static String resolveModel(LlmRequest request, String defaultModel) {
        return request.getModel() != null && !request.getModel().isBlank() ? request.getModel() : defaultModel;
    }
}
