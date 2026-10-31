/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral messages retained in an agent transcript. */
public sealed interface AgentMessage
        permits AgentMessage.Text, AgentMessage.Assistant, AgentMessage.Tool {

    /** Roles for host-supplied text messages. */
    enum Role { SYSTEM, USER }

    /** A system or user text message. */
    record Text(Role role, String content) implements AgentMessage {
        public Text {
            Objects.requireNonNull(role, "role"); //$NON-NLS-1$
            Objects.requireNonNull(content, "content"); //$NON-NLS-1$
        }
    }

    /** An assistant turn containing visible text, optional reasoning, tool calls, or a combination. */
    record Assistant(Optional<String> text, Optional<String> reasoning,
            List<ToolCall> toolCalls) implements AgentMessage {
        public Assistant {
            Objects.requireNonNull(text, "text"); //$NON-NLS-1$
            Objects.requireNonNull(reasoning, "reasoning"); //$NON-NLS-1$
            Objects.requireNonNull(toolCalls, "toolCalls"); //$NON-NLS-1$
            text = text.filter(value -> !value.isEmpty());
            toolCalls = List.copyOf(toolCalls);
            if (text.isEmpty() && reasoning.isEmpty() && toolCalls.isEmpty()) {
                throw new IllegalArgumentException(
                        "assistant message must contain text, reasoning, or tool calls"); //$NON-NLS-1$
            }
        }

        /** Backward-compatible constructor for callers without reasoning content. */
        public Assistant(Optional<String> text, List<ToolCall> toolCalls) {
            this(text, Optional.empty(), toolCalls);
        }

        public static Assistant text(String value) {
            Objects.requireNonNull(value, "value"); //$NON-NLS-1$
            return new Assistant(Optional.of(value), Optional.empty(), List.of());
        }

        public static Assistant tools(List<ToolCall> calls) {
            return new Assistant(Optional.empty(), Optional.empty(), calls);
        }
    }

    /** Result of one tool call, correlated by the assistant call id. */
    record Tool(String callId, String toolName, ToolExecutionResult result) implements AgentMessage {
        public Tool {
            callId = requireText(callId, "callId"); //$NON-NLS-1$
            toolName = requireText(toolName, "toolName"); //$NON-NLS-1$
            Objects.requireNonNull(result, "result"); //$NON-NLS-1$
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); //$NON-NLS-1$
        return value;
    }
}
