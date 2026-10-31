package com.codepilot1c.runtime.mcp;

import com.google.gson.JsonObject;

/** Result of MCP initialize, retaining server-owned JSON objects verbatim. */
public record InitializeResult(String protocolVersion, JsonObject serverInfo,
        JsonObject capabilities, JsonObject experimentalCodepilot, JsonObject rawResult) {
    public InitializeResult {
        serverInfo = copy(serverInfo);
        capabilities = copy(capabilities);
        experimentalCodepilot = copy(experimentalCodepilot);
        rawResult = copy(rawResult);
    }

    @Override public JsonObject serverInfo() { return copy(serverInfo); }
    @Override public JsonObject capabilities() { return copy(capabilities); }
    @Override public JsonObject experimentalCodepilot() { return copy(experimentalCodepilot); }
    @Override public JsonObject rawResult() { return copy(rawResult); }

    private static JsonObject copy(JsonObject value) { return value == null ? null : value.deepCopy(); }
}
