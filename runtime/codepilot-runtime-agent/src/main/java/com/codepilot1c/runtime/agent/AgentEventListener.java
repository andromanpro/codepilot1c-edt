/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

/** Ordered observation hooks for an agent run. */
public interface AgentEventListener {
    AgentEventListener NOOP = new AgentEventListener() { };

    default void onStepStarted(int step) { }

    default void onAssistantDelta(String delta) { }

    default void onAssistantTextDelta(String delta) {
        onAssistantDelta(delta);
    }

    default void onReasoningDelta(String delta) { }

    default void onAssistantReasoningDelta(String delta) {
        onReasoningDelta(delta);
    }

    default void onAssistantMessage(AgentMessage.Assistant message) { }

    default void onToolCallStarted(ToolCall call) { }

    default void onToolCallResult(ToolCall call, ToolExecutionResult result) { }

    default void onTurnFinished(AgentResult result) { }

    default void onStepStarted(String operationId, int step) {
        onStepStarted(step);
    }

    default void onAssistantTextDelta(String operationId, int step, String delta) {
        onAssistantTextDelta(delta);
    }

    default void onAssistantReasoningDelta(String operationId, int step, String delta) {
        onAssistantReasoningDelta(delta);
    }

    default void onAssistantMessage(String operationId, int step, AgentMessage.Assistant message) {
        onAssistantMessage(message);
    }

    default void onToolCallStarted(String operationId, int step, ToolCall call) {
        onToolCallStarted(call);
    }

    default void onToolCallResult(
            String operationId, int step, ToolCall call, ToolExecutionResult result) {
        onToolCallResult(call, result);
    }

    default void onTurnFinished(String operationId, AgentResult result) {
        onTurnFinished(result);
    }
}
