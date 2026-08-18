/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.codepilot1c.runtime.agent.AgentCompletionMode;
import com.codepilot1c.runtime.agent.AgentEventListener;
import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentRequest;
import com.codepilot1c.runtime.agent.AgentResult;
import com.codepilot1c.runtime.agent.AgentRunConfig;
import com.codepilot1c.runtime.agent.AgentRuntime;
import com.codepilot1c.runtime.agent.CancellationSource;
import com.codepilot1c.runtime.agent.ToolApprover;
import com.codepilot1c.runtime.agent.ToolDefinition;

/** Per-turn runtime owner with one-shot expired-MCP recovery and refresh. */
public final class TurnRunner implements AutoCloseable {
    private final ShellEnvironment environment;
    private final AgentRunConfig config;
    private final RuntimeFactory runtimeFactory;
    private final Object lock = new Object();
    private ShellToolSession tools;
    private AgentRuntime activeRuntime;
    private CompletableFuture<AgentResult> activeRun;
    private boolean closed;

    public TurnRunner(ShellEnvironment environment, int maxSteps, Duration timeout) {
        this(environment, new AgentRunConfig(maxSteps, timeout),
                (resolved, session, config, listener, approver) -> new AgentRuntime(
                        resolved.agentModel(), session.runtime(), config, listener, approver,
                        AgentCompletionMode.STREAMING));
    }

    public TurnRunner(ShellEnvironment environment, AgentRunConfig config,
            RuntimeFactory runtimeFactory) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.config = Objects.requireNonNull(config, "config");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.tools = environment.tools();
    }

    /**
     * Runs with the shell's explicit streaming policy at this single seam.
     * The R3 explicit opt-in can be adopted here without changing controller or legacy agent-run code.
     */
    public AgentResult run(String operationId, List<AgentMessage> messages,
            CancellationSource cancellation, AgentEventListener listener, ToolApprover approver)
            throws Exception {
        Objects.requireNonNull(cancellation, "cancellation");
        long deadline = deadline(config.timeout());
        refreshWithRecovery(cancellation, deadline);
        Duration remaining = remaining(deadline);
        if (remaining == null) throw new TimeoutException("shell turn timed out");
        AgentRuntime runtime;
        CompletableFuture<AgentResult> future;
        synchronized (lock) {
            ensureOpen();
            runtime = runtimeFactory.create(environment, tools,
                    new AgentRunConfig(config.maxSteps(), remaining), listener, approver);
            activeRuntime = runtime;
            future = runtime.run(new AgentRequest(operationId, messages), cancellation);
            activeRun = future;
        }
        try {
            return future.get();
        } catch (InterruptedException failure) {
            cancellation.cancel();
            Thread.currentThread().interrupt();
            throw failure;
        } catch (ExecutionException failure) {
            throw exception(unwrap(failure));
        } catch (CancellationException failure) {
            throw failure;
        } finally {
            synchronized (lock) {
                if (activeRun == future) activeRun = null;
                if (activeRuntime == runtime) activeRuntime = null;
            }
            runtime.close();
        }
    }

    public List<ToolDefinition> refreshTools(CancellationSource cancellation) throws Exception {
        refreshWithRecovery(cancellation, deadline(config.timeout()));
        synchronized (lock) { return List.copyOf(tools.runtime().tools()); }
    }

    public void keepalive() throws Exception {
        ShellToolSession visible;
        synchronized (lock) {
            ensureOpen();
            visible = tools;
        }
        try {
            visible.ping().toCompletableFuture().get();
        } catch (ExecutionException failure) {
            Throwable cause = unwrap(failure);
            if (!visible.isExpired(cause)) throw exception(cause);
            replaceExpired(visible, null, Long.MAX_VALUE);
            ShellToolSession replacement;
            synchronized (lock) { replacement = tools; }
            replacement.ping().toCompletableFuture().get();
        }
    }

    public void cancelActive() {
        CompletableFuture<AgentResult> run;
        AgentRuntime runtime;
        synchronized (lock) {
            run = activeRun;
            runtime = activeRuntime;
        }
        if (run != null) run.cancel(true);
        if (runtime != null) runtime.close();
    }

    @Override public void close() {
        AgentRuntime runtime;
        ShellToolSession currentTools;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            runtime = activeRuntime;
            currentTools = tools;
        }
        if (runtime != null) runtime.close();
        try {
            if (currentTools != environment.tools()) currentTools.close();
        } finally {
            environment.close();
        }
    }

    private void refreshWithRecovery(CancellationSource cancellation, long deadline) throws Exception {
        ShellToolSession visible;
        synchronized (lock) {
            ensureOpen();
            visible = tools;
        }
        try {
            await(visible.refresh(), cancellation, deadline);
        } catch (Exception failure) {
            Throwable cause = unwrap(failure);
            if (!visible.isExpired(cause)) throw exception(cause);
            replaceExpired(visible, cancellation, deadline);
            ShellToolSession replacement;
            synchronized (lock) { replacement = tools; }
            await(replacement.refresh(), cancellation, deadline);
        }
    }

    private void replaceExpired(ShellToolSession expected, CancellationSource cancellation, long deadline)
            throws Exception {
        if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
        ShellToolSession replacement = cancellation == null
                ? expected.reinitialize().toCompletableFuture().get()
                : await(expected.reinitialize(), cancellation, deadline);
        boolean publish = false;
        try {
            synchronized (lock) {
                ensureOpen();
                publish = tools == expected;
                if (publish) tools = replacement;
            }
        } finally {
            if (!publish) replacement.close();
        }
        if (publish) expected.close();
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage,
            CancellationSource cancellation, long deadline) throws Exception {
        CompletableFuture<T> future = stage.toCompletableFuture();
        var registration = cancellation.onCancel(() -> future.cancel(true));
        try {
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) throw new TimeoutException("shell turn timed out");
            return future.get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw failure;
        }
        catch (ExecutionException failure) { throw exception(unwrap(failure)); }
        finally { registration.close(); }
    }

    private static long deadline(Duration timeout) {
        long now = System.nanoTime();
        long delta = timeout.toNanos();
        return Long.MAX_VALUE - now < delta ? Long.MAX_VALUE : now + delta;
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? null : Duration.ofNanos(nanos);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("turn runner is closed");
    }

    private static Exception exception(Throwable failure) {
        if (failure instanceof Exception value) return value;
        return new RuntimeException("shell operation failed");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while ((value instanceof ExecutionException || value instanceof CompletionException)
                && value.getCause() != null) value = value.getCause();
        return value;
    }

    @FunctionalInterface
    public interface RuntimeFactory {
        AgentRuntime create(ShellEnvironment environment, ShellToolSession tools,
                AgentRunConfig config, AgentEventListener listener, ToolApprover approver);
    }
}
