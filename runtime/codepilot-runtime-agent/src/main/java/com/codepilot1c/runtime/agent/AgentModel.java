/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Provider-neutral asynchronous completion SPI consumed by the agent loop. */
@FunctionalInterface
public interface AgentModel {
    CompletionStage<AgentMessage.Assistant> complete(Request request, CancellationToken cancellation);

    record Request(List<AgentMessage> messages, List<ToolDefinition> tools) {
        public Request {
            Objects.requireNonNull(messages, "messages"); //$NON-NLS-1$
            Objects.requireNonNull(tools, "tools"); //$NON-NLS-1$
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
            if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty"); //$NON-NLS-1$
        }
    }
}
