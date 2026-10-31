/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.concurrent.CompletionStage;

/** Optional model SPI for incremental assistant text and reasoning. */
@FunctionalInterface
public interface StreamingAgentModel extends AgentModel {
    CompletionStage<AgentMessage.Assistant> complete(
            Request request, CancellationToken cancellation, StreamObserver observer);

    @Override
    default CompletionStage<AgentMessage.Assistant> complete(
            Request request, CancellationToken cancellation) {
        return complete(request, cancellation, StreamObserver.NOOP);
    }
}
