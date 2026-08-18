/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.codepilot1c.runtime.agent.AgentModel;

/** Fully resolved model and MCP resources plus safe status metadata. */
public final class ShellEnvironment implements AutoCloseable {
    private final String mode;
    private final String provider;
    private final String model;
    private final String endpoint;
    private final String instanceId;
    private final AgentModel agentModel;
    private final ShellToolSession tools;
    private final AutoCloseable modelResource;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ShellEnvironment(String mode, String provider, String model, String endpoint,
            String instanceId, AgentModel agentModel, ShellToolSession tools,
            AutoCloseable modelResource) {
        this.mode = require(mode, "mode");
        this.provider = require(provider, "provider");
        this.model = require(model, "model");
        this.endpoint = require(endpoint, "endpoint");
        this.instanceId = instanceId == null || instanceId.isBlank() ? "unregistered" : instanceId;
        this.agentModel = Objects.requireNonNull(agentModel, "agentModel");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.modelResource = modelResource == null ? () -> { } : modelResource;
    }

    public String mode() { return mode; }
    public String provider() { return provider; }
    public String model() { return model; }
    public String endpoint() { return endpoint; }
    public String instanceId() { return instanceId; }
    public AgentModel agentModel() { return agentModel; }
    public ShellToolSession tools() { return tools; }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { tools.close(); }
        finally {
            try { modelResource.close(); }
            catch (Exception ignored) { /* Shell cleanup is best effort. */ }
        }
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
