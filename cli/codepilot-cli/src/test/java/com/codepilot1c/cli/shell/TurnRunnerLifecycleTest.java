/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.codepilot1c.runtime.agent.AgentEventListener;
import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentModel;
import com.codepilot1c.runtime.agent.AgentResult;
import com.codepilot1c.runtime.agent.CancellationSource;
import com.codepilot1c.runtime.agent.CancellationToken;
import com.codepilot1c.runtime.agent.StreamObserver;
import com.codepilot1c.runtime.agent.StreamingAgentModel;
import com.codepilot1c.runtime.agent.ToolApprover;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolExecutionResult;
import com.codepilot1c.runtime.agent.ToolRuntime;
import com.codepilot1c.runtime.provider.ProviderConfiguration;
import com.codepilot1c.runtime.provider.RuntimeProviderFactory;
import com.google.gson.JsonObject;

public class TurnRunnerLifecycleTest {
    @Test public void refreshesThenReinitializesExpiredSessionExactlyOnce() throws Exception {
        FakeSession replacement = new FakeSession(false, null);
        FakeSession expired = new FakeSession(true, replacement);
        ShellEnvironment environment = environment(new ImmediateModel(), expired);
        try (TurnRunner runner = new TurnRunner(environment, 4, Duration.ofSeconds(5))) {
            AgentResult result = runner.run("op", List.of(user()), new CancellationSource(),
                    AgentEventListener.NOOP, ToolApprover.ALLOW);
            assertEquals(AgentResult.Status.COMPLETED, result.status());
            assertEquals(1, expired.refreshes.get());
            assertEquals(1, expired.reinitializations.get());
            assertEquals(1, expired.closes.get());
            assertEquals(1, replacement.refreshes.get());
        }
        assertEquals(1, replacement.closes.get());
    }

    @Test public void keepaliveRecoversExpiredSessionAndCloseIsIdempotent() throws Exception {
        FakeSession replacement = new FakeSession(false, null);
        FakeSession expired = new FakeSession(false, replacement);
        expired.expirePing = true;
        ShellEnvironment environment = environment(new ImmediateModel(), expired);
        TurnRunner runner = new TurnRunner(environment, 4, Duration.ofSeconds(5));
        runner.keepalive();
        assertEquals(1, expired.reinitializations.get());
        assertEquals(1, replacement.pings.get());
        runner.close();
        runner.close();
        assertEquals(1, replacement.closes.get());
    }

    @Test public void cancellationDuringStreamingCompletionPropagatesAndLeavesNoActiveRuntime()
            throws Exception {
        BlockingModel model = new BlockingModel();
        FakeSession tools = new FakeSession(false, null);
        ShellEnvironment environment = environment(model, tools);
        TurnRunner runner = new TurnRunner(environment, 4, Duration.ofSeconds(30));
        CancellationSource cancellation = new CancellationSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> runner.run("stream", List.of(user()), cancellation,
                    AgentEventListener.NOOP, ToolApprover.ALLOW));
            assertTrue(model.started.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            AgentResult terminal = result.get(2, TimeUnit.SECONDS);
            assertEquals(AgentResult.Status.CANCELLED, terminal.status());
            assertTrue(model.cancelled.get());
            runner.close();
            runner.close();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, tools.closes.get());
    }

    @Test public void cancellationDuringToolExecutionCancelsToolFuture() throws Exception {
        CallingModel model = new CallingModel();
        BlockingToolSession tools = new BlockingToolSession();
        ShellEnvironment environment = environment(model, tools);
        TurnRunner runner = new TurnRunner(environment, 4, Duration.ofSeconds(30));
        CancellationSource cancellation = new CancellationSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> runner.run("tool", List.of(user()), cancellation,
                    AgentEventListener.NOOP, ToolApprover.ALLOW));
            assertTrue(tools.started.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            AgentResult terminal = result.get(2, TimeUnit.SECONDS);
            assertEquals(AgentResult.Status.CANCELLED, terminal.status());
            assertTrue(tools.cancelled.get());
        } finally {
            runner.close();
            executor.shutdownNow();
        }
    }

    @Test public void closeRacingExpiredReinitializeClosesLateReplacement() throws Exception {
        CompletableFuture<ShellToolSession> pending = new CompletableFuture<>();
        CountDownLatch reinitializeStarted = new CountDownLatch(1);
        FakeSession replacement = new FakeSession(false, null);
        FakeSession expired = new FakeSession(true, null) {
            @Override public CompletionStage<ShellToolSession> reinitialize() {
                reinitializations.incrementAndGet();
                reinitializeStarted.countDown();
                return pending;
            }
        };
        TurnRunner runner = new TurnRunner(environment(new ImmediateModel(), expired),
                4, Duration.ofSeconds(5));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var refresh = executor.submit(() -> runner.refreshTools(new CancellationSource()));
            assertTrue(reinitializeStarted.await(2, TimeUnit.SECONDS));
            runner.close();
            pending.complete(replacement);
            try { refresh.get(2, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException expected) { /* closed runner */ }
            assertEquals(1, expired.closes.get());
            assertEquals(1, replacement.closes.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test public void shellEnvironmentClosesStandaloneProviderAndWipesItsConfiguration() {
        char[] key = "shell-owned-key".toCharArray();
        ProviderConfiguration configuration = ProviderConfiguration.builder()
                .id("standalone").displayName("Standalone")
                .baseUri(URI.create("https://provider.example/v1")).defaultModel("model")
                .apiKey(key).build();
        var provider = new RuntimeProviderFactory().create(configuration);
        ShellEnvironment environment = new ShellEnvironment("standalone", "provider", "model",
                "http://localhost/mcp", "https://provider.example/v1", "instance",
                new ImmediateModel(), new FakeSession(false, null), provider);

        environment.close();
        environment.close();

        assertFalse(configuration.hasApiKey());
        java.util.Arrays.fill(key, '\0');
    }

    private static AgentMessage.Text user() {
        return new AgentMessage.Text(AgentMessage.Role.USER, "hello");
    }

    private static ShellEnvironment environment(StreamingAgentModel model, ShellToolSession tools) {
        return new ShellEnvironment("connected", "provider", "model", "http://localhost/mcp",
                "instance", model, tools, () -> { });
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        return schema;
    }

    private static final class ImmediateModel implements StreamingAgentModel {
        @Override public CompletionStage<AgentMessage.Assistant> complete(AgentModel.Request request,
                CancellationToken cancellation, StreamObserver observer) {
            observer.onTextDelta("ok");
            return CompletableFuture.completedFuture(AgentMessage.Assistant.text("ok"));
        }
    }

    private static final class BlockingModel implements StreamingAgentModel {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public CompletionStage<AgentMessage.Assistant> complete(AgentModel.Request request,
                CancellationToken cancellation, StreamObserver observer) {
            CompletableFuture<AgentMessage.Assistant> result = new CompletableFuture<>();
            cancellation.onCancel(() -> {
                cancelled.set(true);
                result.cancel(true);
            });
            started.countDown();
            return result;
        }
    }

    private static final class CallingModel implements StreamingAgentModel {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public CompletionStage<AgentMessage.Assistant> complete(AgentModel.Request request,
                CancellationToken cancellation, StreamObserver observer) {
            if (calls.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                        new com.codepilot1c.runtime.agent.ToolCall("call", "write", "{}"))));
            }
            return CompletableFuture.completedFuture(AgentMessage.Assistant.text("done"));
        }
    }

    private static class FakeSession implements ShellToolSession {
        private final boolean expireRefresh;
        private final FakeSession replacement;
        private final AtomicInteger refreshes = new AtomicInteger();
        protected final AtomicInteger reinitializations = new AtomicInteger();
        private final AtomicInteger pings = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean expirePing;
        FakeSession(boolean expireRefresh, FakeSession replacement) {
            this.expireRefresh = expireRefresh;
            this.replacement = replacement;
        }
        @Override public ToolRuntime runtime() { return emptyRuntime(); }
        @Override public CompletionStage<List<ToolDefinition>> refresh() {
            refreshes.incrementAndGet();
            return expireRefresh
                    ? CompletableFuture.failedFuture(new ExpiredFailure())
                    : CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletionStage<Void> ping() {
            pings.incrementAndGet();
            return expirePing ? CompletableFuture.failedFuture(new ExpiredFailure())
                    : CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<ShellToolSession> reinitialize() {
            reinitializations.incrementAndGet();
            return CompletableFuture.completedFuture(replacement);
        }
        @Override public boolean isExpired(Throwable failure) { return failure instanceof ExpiredFailure; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) closes.incrementAndGet();
        }
    }

    private static final class BlockingToolSession extends FakeSession {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        BlockingToolSession() { super(false, null); }
        @Override public ToolRuntime runtime() {
            return new ToolRuntime() {
                @Override public List<ToolDefinition> tools() {
                    return List.of(new ToolDefinition("write", "", schema()));
                }
                @Override public CompletionStage<ToolExecutionResult> execute(String name,
                        JsonObject arguments, CancellationToken cancellation) {
                    CompletableFuture<ToolExecutionResult> future = new CompletableFuture<>();
                    cancellation.onCancel(() -> {
                        cancelled.set(true);
                        future.cancel(true);
                    });
                    started.countDown();
                    return future;
                }
            };
        }
    }

    private static ToolRuntime emptyRuntime() {
        return new ToolRuntime() {
            @Override public List<ToolDefinition> tools() { return List.of(); }
            @Override public CompletionStage<ToolExecutionResult> execute(String name,
                    JsonObject arguments, CancellationToken cancellation) {
                throw new AssertionError();
            }
        };
    }

    private static final class ExpiredFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
