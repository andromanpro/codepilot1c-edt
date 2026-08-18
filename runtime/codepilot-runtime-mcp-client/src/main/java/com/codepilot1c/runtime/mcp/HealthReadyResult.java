package com.codepilot1c.runtime.mcp;

import com.google.gson.JsonObject;

public record HealthReadyResult(int statusCode, boolean ready, JsonObject body) {
    public HealthReadyResult {
        body = body == null ? null : body.deepCopy();
    }
    @Override public JsonObject body() { return body == null ? null : body.deepCopy(); }
}
