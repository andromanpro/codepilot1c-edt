/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Deterministic, machine-readable tool execution outcome. */
public record ToolExecutionResult(boolean error, String code, String message, JsonElement data) {
    public ToolExecutionResult {
        code = requireText(code, "code"); //$NON-NLS-1$
        message = requireText(message, "message"); //$NON-NLS-1$
        data = data == null ? JsonNull.INSTANCE : data.deepCopy();
    }

    @Override
    public JsonElement data() {
        return data.deepCopy();
    }

    public static ToolExecutionResult success(JsonElement data) {
        return new ToolExecutionResult(false, "OK", "Tool completed", data); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static ToolExecutionResult failure(String code, String message) {
        return new ToolExecutionResult(true, code, message, JsonNull.INSTANCE);
    }

    public static ToolExecutionResult failure(String code, String message, JsonElement data) {
        return new ToolExecutionResult(true, code, message, data);
    }

    /** Serializes the stable result envelope passed back to a model. */
    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("ok", !error); //$NON-NLS-1$
        value.addProperty("code", code); //$NON-NLS-1$
        value.addProperty("message", message); //$NON-NLS-1$
        value.add("data", data.deepCopy()); //$NON-NLS-1$
        return value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); //$NON-NLS-1$
        return value;
    }
}
