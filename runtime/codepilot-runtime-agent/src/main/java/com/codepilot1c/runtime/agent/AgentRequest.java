/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.List;
import java.util.Objects;

/** Immutable input for one independently bounded agent run. */
public record AgentRequest(String operationId, List<AgentMessage> messages) {
    public AgentRequest {
        Objects.requireNonNull(operationId, "operationId"); //$NON-NLS-1$
        if (operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank"); //$NON-NLS-1$
        Objects.requireNonNull(messages, "messages"); //$NON-NLS-1$
        messages = List.copyOf(messages);
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty"); //$NON-NLS-1$
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages must not contain null"); //$NON-NLS-1$
        }
    }
}
