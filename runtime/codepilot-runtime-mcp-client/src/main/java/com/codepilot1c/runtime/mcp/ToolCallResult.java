package com.codepilot1c.runtime.mcp;

import com.google.gson.JsonObject;

/** Raw tool result; MCP content and structuredContent are intentionally not narrowed. */
public record ToolCallResult(boolean isError, JsonObject rawResult) {
    public ToolCallResult {
        rawResult = rawResult == null ? null : rawResult.deepCopy();
    }
    @Override public JsonObject rawResult() { return rawResult == null ? null : rawResult.deepCopy(); }
}
