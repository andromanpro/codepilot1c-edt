/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import com.codepilot1c.runtime.agent.McpToolRuntime;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolRuntime;
import com.codepilot1c.runtime.mcp.McpClient;
import com.codepilot1c.runtime.mcp.McpClientException;

/** Production shell ownership wrapper around one initialized runtime MCP client. */
public final class McpShellToolSession implements ShellToolSession {
    private final ClientFactory factory;
    private final ClientResource resource;
    private final McpToolRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpShellToolSession(ClientFactory factory, ClientResource resource,
            McpToolRuntime runtime) {
        this.factory = factory;
        this.resource = resource;
        this.runtime = runtime;
    }

    public static CompletionStage<McpShellToolSession> connect(ClientFactory factory) {
        Objects.requireNonNull(factory, "factory");
        final ClientResource resource;
        try { resource = Objects.requireNonNull(factory.create(), "client resource"); }
        catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        CompletableFuture<McpShellToolSession> result = new CompletableFuture<>();
        McpToolRuntime.connect(resource.client()).whenComplete((runtime, failure) -> {
            if (failure == null) result.complete(new McpShellToolSession(factory, resource, runtime));
            else {
                resource.close();
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) resource.close();
        });
        return result;
    }

    @Override public ToolRuntime runtime() { return runtime; }
    @Override public CompletionStage<List<ToolDefinition>> refresh() { return runtime.refresh(); }
    @Override public CompletionStage<Void> ping() { return resource.client().ping(); }
    @Override public CompletionStage<ShellToolSession> reinitialize() {
        return connect(factory).thenApply(value -> value);
    }

    @Override public boolean isExpired(Throwable failure) {
        Throwable value = unwrap(failure);
        if (!(value instanceof McpClientException mcp)) return false;
        if (mcp.kind() == McpClientException.Kind.JSON_RPC) {
            String message = mcp.getMessage() == null ? "" : mcp.getMessage().toLowerCase(Locale.ROOT);
            return message.contains("session") && (message.contains("invalid")
                    || message.contains("expired") || message.contains("not found")
                    || message.contains("not_found"));
        }
        return mcp.kind() == McpClientException.Kind.STATE
                || (mcp.kind() == McpClientException.Kind.HTTP
                        && (mcp.httpStatus() == 404 || mcp.httpStatus() == 410));
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) resource.close();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while (value instanceof CompletionException && value.getCause() != null) value = value.getCause();
        return value;
    }

    @FunctionalInterface public interface ClientFactory {
        ClientResource create() throws Exception;
    }

    public static final class ClientResource implements AutoCloseable {
        private final McpClient client;
        private final AutoCloseable extra;
        private final AtomicBoolean closed = new AtomicBoolean();
        public ClientResource(McpClient client, AutoCloseable extra) {
            this.client = Objects.requireNonNull(client, "client");
            this.extra = extra == null ? () -> { } : extra;
        }
        public McpClient client() { return client; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { client.close(); }
            finally {
                try { extra.close(); }
                catch (Exception ignored) { /* Best-effort secret/config cleanup. */ }
            }
        }
    }
}
