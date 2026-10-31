/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.util.Objects;

/** Provider-neutral events emitted by an incremental chat completion. */
public sealed interface ProviderStreamEvent permits ProviderStreamEvent.TextDelta,
        ProviderStreamEvent.ReasoningDelta, ProviderStreamEvent.ToolCall,
        ProviderStreamEvent.Usage, ProviderStreamEvent.Done, ProviderStreamEvent.Error {

    /** Incremental assistant-visible text. */
    record TextDelta(String text) implements ProviderStreamEvent {
        public TextDelta {
            Objects.requireNonNull(text, "text"); //$NON-NLS-1$
        }
    }

    /** Incremental reasoning text, when exposed by a compatible provider. */
    record ReasoningDelta(String text) implements ProviderStreamEvent {
        public ReasoningDelta {
            Objects.requireNonNull(text, "text"); //$NON-NLS-1$
        }
    }

    /** A complete tool call assembled from all fragments for its index. */
    record ToolCall(int index, String id, String name, String argumentsJson) implements ProviderStreamEvent {
        public ToolCall {
            if (index < 0) throw new IllegalArgumentException("index must be non-negative"); //$NON-NLS-1$
            id = requireText(id, "id"); //$NON-NLS-1$
            name = requireText(name, "name"); //$NON-NLS-1$
            Objects.requireNonNull(argumentsJson, "argumentsJson"); //$NON-NLS-1$
        }
    }

    /** Provider token accounting, normalized to input/output terminology. */
    record Usage(long inputTokens, long outputTokens, long totalTokens) implements ProviderStreamEvent {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("token counts must be non-negative"); //$NON-NLS-1$
            }
        }
    }

    /** Terminal marker emitted after all accumulated tool calls. */
    record Done() implements ProviderStreamEvent {
    }

    /** Terminal failure marker; the returned future fails with the same value. */
    record Error(ProviderStreamException failure) implements ProviderStreamEvent {
        public Error {
            Objects.requireNonNull(failure, "failure"); //$NON-NLS-1$
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); //$NON-NLS-1$
        return value;
    }
}
