/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.codepilot1c.runtime.mcp.McpClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Adapts an initialized MCP session to the provider-neutral tool SPI. */
public final class McpToolRuntime implements ToolRuntime {
    private final McpClient client;
    private final List<ToolDefinition> definitions;
    private final Set<String> names;

    private McpToolRuntime(McpClient client, List<ToolDefinition> definitions) {
        this.client = client;
        this.definitions = List.copyOf(definitions);
        this.names = Set.copyOf(definitions.stream().map(ToolDefinition::name).toList());
        if (names.size() != definitions.size()) {
            throw new IllegalArgumentException("MCP tool names must be unique"); //$NON-NLS-1$
        }
    }

    /** Initializes when needed and captures one stable MCP tool snapshot. */
    public static CompletionStage<McpToolRuntime> connect(McpClient client) {
        Objects.requireNonNull(client, "client"); //$NON-NLS-1$
        CompletionStage<?> initialization = client.isInitialized()
                ? CompletableFuture.completedFuture(null)
                : client.initialize();
        return initialization.thenCompose(ignored -> client.listTools()).thenApply(listed -> {
            List<ToolDefinition> definitions = listed.tools().stream()
                    .map(tool -> new ToolDefinition(tool.name(), tool.description(), schema(tool.inputSchema())))
                    .toList();
            return new McpToolRuntime(client, definitions);
        });
    }

    @Override
    public List<ToolDefinition> tools() {
        return definitions;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String name, JsonObject arguments, CancellationToken cancellation) {
        Objects.requireNonNull(name, "name"); //$NON-NLS-1$
        Objects.requireNonNull(arguments, "arguments"); //$NON-NLS-1$
        Objects.requireNonNull(cancellation, "cancellation"); //$NON-NLS-1$
        if (!names.contains(name)) {
            return CompletableFuture.completedFuture(ToolExecutionResult.failure(
                    "UNKNOWN_TOOL", "Requested tool is not available")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (cancellation.isCancelled()) {
            CompletableFuture<ToolExecutionResult> cancelled = new CompletableFuture<>();
            cancelled.cancel(false);
            return cancelled;
        }
        CompletableFuture<com.codepilot1c.runtime.mcp.ToolCallResult> future =
                client.callTool(name, arguments);
        CancellationToken.Registration registration = cancellation.onCancel(() -> future.cancel(true));
        return future.whenComplete((ignored, failure) -> registration.close()).thenApply(result ->
                result.isError()
                    ? ToolExecutionResult.failure("MCP_TOOL_ERROR", "MCP tool reported an error", //$NON-NLS-1$ //$NON-NLS-2$
                            result.rawResult())
                    : ToolExecutionResult.success(result.rawResult()));
    }

    private static JsonObject schema(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("MCP tool input schema must be a JSON object"); //$NON-NLS-1$
        }
        return value.getAsJsonObject();
    }
}
