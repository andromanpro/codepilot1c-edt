package com.codepilot1c.runtime.mcp;

import java.util.List;
import com.google.gson.JsonObject;

public record ToolsListResult(List<ToolDefinition> tools, JsonObject rawResult) {
    public ToolsListResult {
        tools = List.copyOf(tools);
        rawResult = rawResult == null ? null : rawResult.deepCopy();
    }
    @Override public JsonObject rawResult() { return rawResult == null ? null : rawResult.deepCopy(); }
}
