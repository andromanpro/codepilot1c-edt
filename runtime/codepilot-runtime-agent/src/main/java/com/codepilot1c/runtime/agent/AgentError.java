/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.Objects;

/** Stable error returned by a non-completed agent run. */
public record AgentError(Code code, String message) {
    public enum Code {
        CANCELLED,
        CLOSED,
        TIMEOUT,
        STEP_LIMIT,
        PROVIDER_TRANSPORT,
        PROVIDER_AUTH,
        PROVIDER_HTTP,
        PROVIDER_RESPONSE,
        TOOL_CATALOG,
        TOOL_APPROVAL
    }

    public AgentError {
        Objects.requireNonNull(code, "code"); //$NON-NLS-1$
        Objects.requireNonNull(message, "message"); //$NON-NLS-1$
        if (message.isBlank()) throw new IllegalArgumentException("message must not be blank"); //$NON-NLS-1$
    }
}
