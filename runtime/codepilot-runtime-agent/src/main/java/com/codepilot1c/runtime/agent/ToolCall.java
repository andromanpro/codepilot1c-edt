/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.Objects;

/** Provider-neutral assistant request to invoke a named tool. */
public record ToolCall(String id, String name, String argumentsJson) {
    public ToolCall {
        id = requireText(id, "id"); //$NON-NLS-1$
        name = requireText(name, "name"); //$NON-NLS-1$
        Objects.requireNonNull(argumentsJson, "argumentsJson"); //$NON-NLS-1$
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); //$NON-NLS-1$
        return value;
    }
}
