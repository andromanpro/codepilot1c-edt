package com.codepilot1c.runtime.mcp;

import com.google.gson.JsonElement;

/** Provider-neutral MCP tool metadata. The input schema remains raw JSON. */
public record ToolDefinition(String name, String description, JsonElement inputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
        inputSchema = inputSchema == null ? null : inputSchema.deepCopy();
    }
    @Override public JsonElement inputSchema() { return inputSchema == null ? null : inputSchema.deepCopy(); }
}
