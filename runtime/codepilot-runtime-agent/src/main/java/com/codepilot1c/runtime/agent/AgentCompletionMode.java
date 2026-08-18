/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

/** Selects how an {@link AgentRuntime} invokes a streaming-capable model. */
public enum AgentCompletionMode {
    /** Use the original buffered {@link AgentModel} completion contract. */
    BUFFERED,
    /** Use {@link StreamingAgentModel} and publish incremental events. */
    STREAMING
}
