/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Normalizes model-produced tool-call arguments for OpenAI-compatible replay.
 */
final class ToolCallArguments {

    private ToolCallArguments() {
    }

    static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("{}"); //$NON-NLS-1$
        }

        String normalized = raw.strip();
        Optional<String> parsed = parseObjectString(normalized);
        if (parsed.isPresent()) {
            return parsed;
        }

        String repaired = JsonRepairUtil.repair(normalized);
        if (!repaired.equals(normalized)) {
            return parseObjectString(repaired);
        }
        return Optional.empty();
    }

    static Optional<String> normalize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.of("{}"); //$NON-NLS-1$
        }
        if (element.isJsonObject()) {
            return Optional.of(element.getAsJsonObject().toString());
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String raw = element.getAsString();
            if (raw == null || raw.isBlank() || raw.strip().startsWith("{")) { //$NON-NLS-1$
                return normalize(raw);
            }
        }
        return Optional.empty();
    }

    static boolean isJsonObjectString(String raw) {
        return normalize(raw).isPresent();
    }

    private static Optional<String> parseObjectString(String value) {
        try {
            JsonElement element = JsonParser.parseString(value);
            if (element == null || element.isJsonNull()) {
                return Optional.of("{}"); //$NON-NLS-1$
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                return Optional.of(object.toString());
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String nested = element.getAsString();
                if (nested != null && nested.strip().startsWith("{")) { //$NON-NLS-1$
                    return normalize(nested);
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
