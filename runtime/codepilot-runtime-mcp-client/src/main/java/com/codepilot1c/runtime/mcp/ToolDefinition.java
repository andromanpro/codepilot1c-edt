package com.codepilot1c.runtime.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** MCP tool metadata. JSON schemas, annotations, and extension metadata remain raw JSON. */
public record ToolDefinition(String name, String description, JsonElement inputSchema,
        JsonObject annotations, JsonObject metadata) {
    public ToolDefinition(String name, String description, JsonElement inputSchema) {
        this(name, description, inputSchema, null, null);
    }

    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
        inputSchema = inputSchema == null ? null : inputSchema.deepCopy();
        annotations = annotations == null ? null : annotations.deepCopy();
        metadata = metadata == null ? null : metadata.deepCopy();
    }

    @Override public JsonElement inputSchema() { return inputSchema == null ? null : inputSchema.deepCopy(); }
    @Override public JsonObject annotations() { return annotations == null ? null : annotations.deepCopy(); }
    @Override public JsonObject metadata() { return metadata == null ? null : metadata.deepCopy(); }
}
