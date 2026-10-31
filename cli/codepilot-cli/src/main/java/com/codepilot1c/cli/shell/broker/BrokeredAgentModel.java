/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.broker;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentModel.Request;
import com.codepilot1c.runtime.agent.CancellationToken;
import com.codepilot1c.runtime.agent.StreamObserver;
import com.codepilot1c.runtime.agent.StreamingAgentModel;

/** Streaming agent model backed by the active provider configured in EDT. */
public final class BrokeredAgentModel implements StreamingAgentModel {
    private final BrokerClient client;

    public BrokeredAgentModel(BrokerClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public CompletionStage<AgentMessage.Assistant> complete(
            Request request, CancellationToken cancellation, StreamObserver observer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(observer, "observer");
        return client.complete(request, cancellation, observer);
    }
}
