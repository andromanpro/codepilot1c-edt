/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

/** Receives incremental content from a streaming model completion. */
public interface StreamObserver {
    StreamObserver NOOP = new StreamObserver() { };

    /** Called for each assistant text fragment, in provider order. */
    default void onTextDelta(String delta) { }

    /** Alias for hosts that describe visible assistant text as an assistant delta. */
    default void onAssistantDelta(String delta) {
        onTextDelta(delta);
    }

    /** Called for each assistant reasoning fragment, in provider order. */
    default void onReasoningDelta(String delta) { }
}
