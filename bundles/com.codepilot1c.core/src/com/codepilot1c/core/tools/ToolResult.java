/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Result of executing a tool.
 *
 * <p>Supports optional structured data alongside text content.
 * The text content is always sent to the LLM; the structured data
 * is available for programmatic access by composite tools, chained
 * tool calls, and sub-agent orchestration without re-parsing text.</p>
 */
public class ToolResult {

    private static final String LLM_TRUNCATION_HEADER =
            "[tool result truncated by CodePilot1C]\n"; //$NON-NLS-1$
    private static final String LLM_TRUNCATION_SEPARATOR =
            "\n\n...[truncated middle]...\n\n"; //$NON-NLS-1$

    private final boolean success;
    private final String content;
    private final String errorMessage;
    private final ToolResultType type;
    private final JsonObject structuredData;

    private ToolResult(boolean success, String content, String errorMessage,
            ToolResultType type, JsonObject structuredData) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
        this.type = type;
        this.structuredData = structuredData;
    }

    /**
     * Creates a successful text result.
     *
     * @param content the result content
     * @return the tool result
     */
    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, ToolResultType.TEXT, null);
    }

    /**
     * Creates a successful result with specific type.
     *
     * @param content the result content
     * @param type the result type
     * @return the tool result
     */
    public static ToolResult success(String content, ToolResultType type) {
        return new ToolResult(true, content, null, type, null);
    }

    /**
     * Creates a successful result with structured data.
     *
     * <p>The text content is sent to the LLM as usual. The structured
     * data is available via {@link #getStructuredData()} for programmatic
     * consumers (composite tools, delegation, chaining).</p>
     *
     * @param content the text content for the LLM
     * @param structured machine-readable structured data
     * @return the tool result
     */
    public static ToolResult success(String content, JsonObject structured) {
        return new ToolResult(true, content, null, ToolResultType.TEXT, structured);
    }

    /**
     * Creates a successful result with type and structured data.
     *
     * @param content the text content for the LLM
     * @param type the result type
     * @param structured machine-readable structured data
     * @return the tool result
     */
    public static ToolResult success(String content, ToolResultType type, JsonObject structured) {
        return new ToolResult(true, content, null, type, structured);
    }

    /**
     * Creates a failure result.
     *
     * @param errorMessage the error message
     * @return the tool result
     */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage, ToolResultType.ERROR, null);
    }

    /**
     * Creates a failure result with machine-readable structured data.
     *
     * @param errorMessage the error message
     * @param structured machine-readable structured data
     * @return the tool result
     */
    public static ToolResult failure(String errorMessage, JsonObject structured) {
        return new ToolResult(false, null, errorMessage, ToolResultType.ERROR, structured);
    }

    /**
     * Returns whether the tool execution was successful.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the result content.
     *
     * @return the content, or null if failed
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the error message if execution failed.
     *
     * @return the error message, or null if successful
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the result type.
     *
     * @return the result type
     */
    public ToolResultType getType() {
        return type;
    }

    /**
     * Returns the structured data, or {@code null} if not provided.
     *
     * @return the structured data object, or null
     */
    public JsonObject getStructuredData() {
        return structuredData;
    }

    /**
     * Returns whether this result carries structured data.
     *
     * @return true if structured data is present
     */
    public boolean hasStructuredData() {
        return structuredData != null && !structuredData.isEmpty();
    }

    /**
     * Retrieves a typed value from the structured data.
     *
     * @param key the JSON key
     * @return the value as a string, or {@code null} if absent or not structured
     */
    public String getStructuredString(String key) {
        if (structuredData == null || !structuredData.has(key)) {
            return null;
        }
        JsonElement element = structuredData.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    /**
     * Retrieves an integer value from the structured data.
     *
     * @param key the JSON key
     * @param defaultValue the default if absent
     * @return the integer value, or defaultValue
     */
    public int getStructuredInt(String key, int defaultValue) {
        if (structuredData == null || !structuredData.has(key)) {
            return defaultValue;
        }
        try {
            return structuredData.get(key).getAsInt();
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /**
     * Retrieves a nested JSON object from the structured data.
     *
     * @param key the JSON key
     * @return the nested object, or {@code null}
     */
    public JsonObject getStructuredObject(String key) {
        if (structuredData == null || !structuredData.has(key)) {
            return null;
        }
        JsonElement element = structuredData.get(key);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * Returns the content to send back to the LLM.
     *
     * @return the content or error message
     */
    public String getContentForLlm() {
        if (success) {
            return content;
        } else {
            return "Error: " + errorMessage; //$NON-NLS-1$
        }
    }

    /**
     * Returns bounded content to send back to the LLM while keeping the full
     * {@link #getContent()} payload available for UI/tool-card rendering.
     *
     * @param maxChars maximum number of characters to place into model history
     * @return original content when within the cap, otherwise a head/tail excerpt
     */
    public String getContentForLlm(int maxChars) {
        String raw = getContentForLlm();
        if (raw == null || maxChars <= 0 || raw.length() <= maxChars) {
            return raw;
        }

        String metadata = LLM_TRUNCATION_HEADER
                + "original_length_chars: " + raw.length() + '\n' //$NON-NLS-1$
                + "result_type: " + type + '\n' //$NON-NLS-1$
                + "full_result_visible_in_tool_card: true\n\n"; //$NON-NLS-1$
        if (metadata.length() >= maxChars) {
            return buildCompactTruncatedContent(raw, maxChars);
        }

        int excerptBudget = maxChars - metadata.length();
        if (excerptBudget <= LLM_TRUNCATION_SEPARATOR.length()) {
            return metadata + raw.substring(0, excerptBudget);
        }

        int contentBudget = excerptBudget - LLM_TRUNCATION_SEPARATOR.length();
        if (contentBudget < 64) {
            return metadata + raw.substring(0, Math.min(excerptBudget, raw.length()));
        }
        int headChars = (contentBudget * 2) / 3;
        int tailChars = contentBudget - headChars;
        if (headChars + tailChars >= raw.length()) {
            return metadata + raw.substring(0, excerptBudget);
        }

        StringBuilder builder = new StringBuilder(maxChars);
        builder.append(metadata);
        builder.append(raw, 0, headChars);
        builder.append(LLM_TRUNCATION_SEPARATOR);
        if (tailChars > 0) {
            builder.append(raw, raw.length() - tailChars, raw.length());
        }
        return builder.toString();
    }

    private String buildCompactTruncatedContent(String raw, int maxChars) {
        String metadata = LLM_TRUNCATION_HEADER
                + "original_length_chars: " + raw.length() + '\n' //$NON-NLS-1$
                + "result_type: " + type + "\n\n"; //$NON-NLS-1$
        if (metadata.length() >= maxChars) {
            return metadata.substring(0, maxChars);
        }
        int contentBudget = maxChars - metadata.length();
        return metadata + raw.substring(0, Math.min(contentBudget, raw.length()));
    }

    /**
     * Type of tool result.
     */
    public enum ToolResultType {
        /** Plain text result */
        TEXT,
        /** Code/file content */
        CODE,
        /** Search results with multiple items */
        SEARCH_RESULTS,
        /** File list */
        FILE_LIST,
        /** Confirmation of action taken */
        CONFIRMATION,
        /** Error result */
        ERROR
    }
}
