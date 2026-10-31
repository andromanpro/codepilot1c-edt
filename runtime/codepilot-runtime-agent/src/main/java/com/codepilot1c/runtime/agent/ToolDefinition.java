/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Provider-neutral tool metadata with an object-shaped JSON input schema. */
public record ToolDefinition(String name, String description, JsonObject inputSchema,
        Optional<ToolAnnotations> annotations) {
    public ToolDefinition(String name, String description, JsonObject inputSchema) {
        this(name, description, inputSchema, Optional.empty());
    }

    public ToolDefinition(String name, String description, JsonObject inputSchema,
            ToolAnnotations annotations) {
        this(name, description, inputSchema, Optional.ofNullable(annotations));
    }

    public ToolDefinition {
        name = requireText(name, "name"); //$NON-NLS-1$
        description = description == null ? "" : description;
        Objects.requireNonNull(inputSchema, "inputSchema"); //$NON-NLS-1$
        Objects.requireNonNull(annotations, "annotations"); //$NON-NLS-1$
        JsonElement type = inputSchema.get("type"); //$NON-NLS-1$
        if (type == null || !type.isJsonPrimitive() || !"object".equals(type.getAsString())) { //$NON-NLS-1$
            throw new IllegalArgumentException("inputSchema must declare type=object"); //$NON-NLS-1$
        }
        inputSchema = inputSchema.deepCopy();
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); //$NON-NLS-1$
        return value;
    }
}
