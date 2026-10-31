/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.codepilot1c.runtime.mcp.McpClient;
import com.codepilot1c.runtime.mcp.McpClientException;
import com.codepilot1c.runtime.mcp.ToolsListResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Adapts an initialized MCP session to the provider-neutral tool SPI. */
public final class McpToolRuntime implements ToolRuntime {
    private final McpClient client;
    private final AtomicReference<Catalog> catalog;
    private final AtomicLong refreshSequence = new AtomicLong();

    private McpToolRuntime(McpClient client, List<ToolDefinition> definitions) {
        this.client = client;
        this.catalog = new AtomicReference<>(catalog(definitions));
    }

    /** Initializes when needed and captures one stable MCP tool snapshot. */
    public static CompletionStage<McpToolRuntime> connect(McpClient client) {
        Objects.requireNonNull(client, "client"); //$NON-NLS-1$
        CompletionStage<?> initialization = client.isInitialized()
                ? CompletableFuture.completedFuture(null)
                : client.initialize();
        CompletableFuture<McpToolRuntime> connecting = initialization
                .thenCompose(ignored -> client.listTools())
                .thenApply(listed -> new McpToolRuntime(client, definitions(listed)))
                .toCompletableFuture();
        CompletableFuture<McpToolRuntime> guarded = new CompletableFuture<>();
        connecting.whenComplete((runtime, failure) -> {
            if (guarded.isDone()) return;
            if (failure == null) {
                guarded.complete(runtime);
                return;
            }
            client.closeAsync().whenComplete((ignored, closeFailure) ->
                    guarded.completeExceptionally(failure));
        });
        guarded.whenComplete((ignored, failure) -> {
            if (!guarded.isCancelled()) return;
            connecting.cancel(true);
            client.closeAsync();
        });
        return guarded;
    }

    @Override
    public List<ToolDefinition> tools() {
        return catalog.get().definitions();
    }

    /**
     * Lists the host tools asynchronously and publishes the new immutable catalog only
     * after the complete response has been validated. A failed or cancelled refresh
     * leaves the currently visible catalog unchanged. If refreshes overlap, only the
     * most recently started successful refresh may publish its result.
     */
    public CompletionStage<List<ToolDefinition>> refresh() {
        long sequence = refreshSequence.incrementAndGet();
        final CompletableFuture<ToolsListResult> listing;
        try {
            listing = client.listTools();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        RefreshOperation operation = new RefreshOperation(sequence, listing);
        operation.start();
        return operation.future;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String name, JsonObject arguments, CancellationToken cancellation) {
        Objects.requireNonNull(name, "name"); //$NON-NLS-1$
        Objects.requireNonNull(arguments, "arguments"); //$NON-NLS-1$
        Objects.requireNonNull(cancellation, "cancellation"); //$NON-NLS-1$
        Catalog visible = catalog.get();
        if (!visible.names().contains(name)) {
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
        CompletableFuture<ToolExecutionResult> mapped = new CompletableFuture<>();
        future.whenComplete((result, failure) -> {
            registration.close();
            if (failure != null) {
                mapped.completeExceptionally(failure);
            } else if (result.isError()) {
                mapped.complete(ToolExecutionResult.failure(
                        "MCP_TOOL_ERROR", "MCP tool reported an error", result.rawResult())); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                mapped.complete(ToolExecutionResult.success(result.rawResult()));
            }
        });
        mapped.whenComplete((ignored, failure) -> {
            if (mapped.isCancelled()) future.cancel(true);
        });
        return mapped;
    }

    private static JsonObject schema(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("MCP tool input schema must be a JSON object"); //$NON-NLS-1$
        }
        return value.getAsJsonObject();
    }

    private static List<ToolDefinition> definitions(ToolsListResult listed) {
        return listed.tools().stream().map(tool -> new ToolDefinition(
                tool.name(), tool.description(), schema(tool.inputSchema()), annotations(tool)))
                .toList();
    }

    private static Optional<ToolAnnotations> annotations(
            com.codepilot1c.runtime.mcp.ToolDefinition tool) {
        JsonObject standard = tool.annotations();
        JsonObject metadata = tool.metadata();
        if (standard == null && metadata == null) return Optional.empty();
        return Optional.of(new ToolAnnotations(
                stringValue(standard, "title"), //$NON-NLS-1$
                booleanValue(standard, "destructiveHint"), //$NON-NLS-1$
                booleanValue(standard, "readOnlyHint"), //$NON-NLS-1$
                booleanValue(metadata, "codepilot1c/requiresConfirmation"))); //$NON-NLS-1$
    }

    private static String stringValue(JsonObject object, String name) {
        if (object == null) return ""; //$NON-NLS-1$
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return ""; //$NON-NLS-1$
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : ""; //$NON-NLS-1$
    }

    private static boolean booleanValue(JsonObject object, String name) {
        if (object == null) return false;
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isBoolean() && primitive.getAsBoolean();
    }

    private static Catalog catalog(List<ToolDefinition> definitions) {
        List<ToolDefinition> immutable = List.copyOf(definitions);
        Set<String> names = Set.copyOf(immutable.stream().map(ToolDefinition::name).toList());
        if (names.size() != immutable.size()) {
            throw new IllegalArgumentException("MCP tool names must be unique"); //$NON-NLS-1$
        }
        return new Catalog(immutable, names);
    }

    private final class RefreshOperation {
        private final long sequence;
        private final CompletableFuture<ToolsListResult> listing;
        private final Object lock = new Object();
        private final RefreshFuture future = new RefreshFuture(this);
        private boolean terminal;

        RefreshOperation(long sequence, CompletableFuture<ToolsListResult> listing) {
            this.sequence = sequence;
            this.listing = listing;
        }

        void start() {
            listing.whenComplete((listed, failure) -> {
                if (failure != null) {
                    completeFailure(failure);
                    return;
                }
                final Catalog replacement;
                try {
                    replacement = catalog(definitions(listed));
                } catch (RuntimeException validationFailure) {
                    completeFailure(validationFailure);
                    return;
                }
                McpClientException closedFailure = null;
                synchronized (lock) {
                    if (terminal) return;
                    terminal = true;
                    if (!client.isInitialized()) {
                        closedFailure = new McpClientException(McpClientException.Kind.STATE,
                                "MCP client closed during tool refresh"); //$NON-NLS-1$
                    } else if (refreshSequence.get() == sequence) {
                        catalog.set(replacement);
                    }
                }
                if (closedFailure == null) future.completeInternal(replacement.definitions());
                else future.completeExceptionallyInternal(closedFailure);
            });
        }

        void completeFailure(Throwable failure) {
            synchronized (lock) {
                if (terminal) return;
                terminal = true;
            }
            future.completeExceptionallyInternal(failure);
        }

        boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lock) {
                if (terminal) return false;
                terminal = true;
            }
            boolean cancelled = future.cancelInternal(mayInterruptIfRunning);
            listing.cancel(mayInterruptIfRunning);
            return cancelled;
        }
    }

    private static final class RefreshFuture extends CompletableFuture<List<ToolDefinition>> {
        private final RefreshOperation operation;

        RefreshFuture(RefreshOperation operation) {
            this.operation = operation;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return operation.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean complete(List<ToolDefinition> value) {
            return false;
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            return false;
        }

        @Override
        public void obtrudeValue(List<ToolDefinition> value) {
            throw externalMutation();
        }

        @Override
        public void obtrudeException(Throwable failure) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<List<ToolDefinition>> completeAsync(
                Supplier<? extends List<ToolDefinition>> supplier) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<List<ToolDefinition>> completeAsync(
                Supplier<? extends List<ToolDefinition>> supplier,
                java.util.concurrent.Executor executor) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<List<ToolDefinition>> orTimeout(long timeout, TimeUnit unit) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<List<ToolDefinition>> completeOnTimeout(
                List<ToolDefinition> value, long timeout, TimeUnit unit) {
            throw externalMutation();
        }

        boolean cancelInternal(boolean mayInterruptIfRunning) {
            return super.cancel(mayInterruptIfRunning);
        }

        boolean completeInternal(List<ToolDefinition> value) {
            return super.complete(value);
        }

        boolean completeExceptionallyInternal(Throwable failure) {
            return super.completeExceptionally(failure);
        }

        private UnsupportedOperationException externalMutation() {
            return new UnsupportedOperationException(
                    "MCP tool refresh completion is owned by McpToolRuntime; use cancel() to stop it"); //$NON-NLS-1$
        }
    }

    private record Catalog(List<ToolDefinition> definitions, Set<String> names) { }
}
