/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.shell.session.SessionStore;
import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentModel;
import com.codepilot1c.runtime.agent.CancellationToken;
import com.codepilot1c.runtime.agent.StreamObserver;
import com.codepilot1c.runtime.agent.StreamingAgentModel;
import com.codepilot1c.runtime.agent.ToolAnnotations;
import com.codepilot1c.runtime.agent.ToolCall;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolExecutionResult;
import com.codepilot1c.runtime.agent.ToolRuntime;
import com.google.gson.JsonObject;

public class ShellInterruptTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void firstCtrlCCancelsSseTurnAndShellContinues() throws Exception {
        BlockingStreamModel model = new BlockingStreamModel();
        TestTools tools = new TestTools(false);
        TestTerminal terminal = new TestTerminal("stream", "/exit");
        ShellController controller = controller(terminal, model, tools, "sse");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (controller) {
            var result = executor.submit(controller::run);
            assertTrue(model.started.await(2, TimeUnit.SECONDS));
            controller.interrupt();
            assertEquals(ExitCodes.OK, (int) result.get(2, TimeUnit.SECONDS));
            assertTrue(model.cancelled.get());
            assertTrue(terminal.output.toString().toLowerCase(java.util.Locale.ROOT)
                    .contains("cancelled"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test public void firstCtrlCCancelsToolExecutionAndSecondInterruptExitsCleanly() throws Exception {
        ToolCallingModel model = new ToolCallingModel();
        TestTools tools = new TestTools(true);
        TestTerminal terminal = new TestTerminal("tool");
        ShellController controller = controller(terminal, model, tools, "tool");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (controller) {
            var result = executor.submit(controller::run);
            assertTrue(tools.started.await(2, TimeUnit.SECONDS));
            controller.interrupt();
            controller.interrupt();
            assertEquals(ExitCodes.OK, (int) result.get(2, TimeUnit.SECONDS));
            assertTrue(tools.cancelled.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, tools.closes.get());
    }

    private ShellController controller(TestTerminal terminal, StreamingAgentModel model,
            TestTools tools, String directory) throws Exception {
        Path root = temporary.newFolder(directory).toPath();
        SessionStore sessions = new SessionStore(root, value -> value, warning -> { });
        ShellOptions options = new ShellOptions(ShellOptions.Mode.CONNECTED, "instance", null,
                null, false, null, null, null, null, false, 4, 30, null);
        ShellEnvironment environment = new ShellEnvironment("connected", "provider", "model",
                "http://localhost/mcp", "instance", model, tools, () -> { });
        return new ShellController(terminal, options, ignored -> environment, sessions, "", value -> value,
                ping -> new IdleKeepalive(Duration.ofDays(1), ping, new NoScheduler()));
    }

    private static JsonObject schema() {
        JsonObject value = new JsonObject();
        value.addProperty("type", "object");
        return value;
    }

    private static final class BlockingStreamModel implements StreamingAgentModel {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public CompletionStage<AgentMessage.Assistant> complete(AgentModel.Request request,
                CancellationToken cancellation, StreamObserver observer) {
            CompletableFuture<AgentMessage.Assistant> result = new CompletableFuture<>();
            observer.onTextDelta("partial");
            cancellation.onCancel(() -> {
                cancelled.set(true);
                result.cancel(true);
            });
            started.countDown();
            return result;
        }
    }

    private static final class ToolCallingModel implements StreamingAgentModel {
        @Override public CompletionStage<AgentMessage.Assistant> complete(AgentModel.Request request,
                CancellationToken cancellation, StreamObserver observer) {
            return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(List.of(
                    new ToolCall("call", "slow_tool", "{}"))));
        }
    }

    private static final class TestTools implements ShellToolSession {
        private final boolean blocking;
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        TestTools(boolean blocking) { this.blocking = blocking; }
        @Override public ToolRuntime runtime() {
            return new ToolRuntime() {
                @Override public List<ToolDefinition> tools() {
                    if (!blocking) return List.of();
                    return List.of(new ToolDefinition("slow_tool", "", schema(),
                            new ToolAnnotations("Slow", false, true, false)));
                }
                @Override public CompletionStage<ToolExecutionResult> execute(String name,
                        JsonObject arguments, CancellationToken cancellation) {
                    CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
                    cancellation.onCancel(() -> {
                        cancelled.set(true);
                        result.cancel(true);
                    });
                    started.countDown();
                    return result;
                }
            };
        }
        @Override public CompletionStage<List<ToolDefinition>> refresh() {
            return CompletableFuture.completedFuture(runtime().tools());
        }
        @Override public CompletionStage<Void> ping() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<ShellToolSession> reinitialize() {
            return CompletableFuture.completedFuture(this);
        }
        @Override public boolean isExpired(Throwable failure) { return false; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) closes.incrementAndGet();
        }
    }

    private static final class TestTerminal implements ShellTerminal {
        private final Deque<String> lines = new ArrayDeque<>();
        private final StringBuilder output = new StringBuilder();
        TestTerminal(String... lines) { this.lines.addAll(List.of(lines)); }
        @Override public synchronized String readLine(String prompt) {
            output.append(prompt);
            return lines.isEmpty() ? null : lines.removeFirst();
        }
        @Override public synchronized void println(String text) { output.append(text).append('\n'); }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static final class NoScheduler implements IdleKeepalive.Scheduler {
        @Override public IdleKeepalive.Cancellable schedule(Runnable task, Duration delay) {
            return () -> { };
        }
        @Override public void close() { }
    }
}
