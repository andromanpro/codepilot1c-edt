/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test public void ctrlCDuringAsyncConfirmationAbortsAndAwaitsTheOnlyTerminalReader()
            throws Exception {
        AsyncToolCallingModel model = new AsyncToolCallingModel();
        ApprovalTools tools = new ApprovalTools();
        BlockingApprovalTerminal terminal = new BlockingApprovalTerminal();
        ShellController controller = controller(terminal, model, tools, "approval");
        ExecutorService controllerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService completionExecutor = Executors.newSingleThreadExecutor();
        try (controller) {
            var result = controllerExecutor.submit(controller::run);
            assertTrue(model.started.await(2, TimeUnit.SECONDS));
            var completion = completionExecutor.submit(model::completeWithToolCall);
            assertTrue(terminal.approvalStarted.await(2, TimeUnit.SECONDS));

            controller.interrupt();

            assertEquals(ExitCodes.OK, (int) result.get(2, TimeUnit.SECONDS));
            completion.get(2, TimeUnit.SECONDS);
            assertTrue(terminal.approvalExited.get());
            assertTrue(terminal.aborts.get() > 0);
            assertEquals(1, terminal.maximumReaders.get());
            assertEquals(0, terminal.activeReaders.get());
            assertFalse(tools.executed.get());
        } finally {
            controllerExecutor.shutdownNow();
            completionExecutor.shutdownNow();
        }
    }

    @Test public void nonemptyPromptIsClearedAndSecondInterruptWithinWindowExits()
            throws Exception {
        PromptInterruptTerminal terminal = new PromptInterruptTerminal("draft", "again");
        AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        Path root = temporary.newFolder("prompt-interrupt").toPath();
        SessionStore sessions = new SessionStore(root, value -> value, warning -> { });
        ShellOptions options = new ShellOptions(ShellOptions.Mode.CONNECTED, "instance", null,
                null, false, null, null, null, null, false, 4, 30, null);
        ShellController controller = new ShellController(terminal, options,
                ignored -> { throw new AssertionError("no turn should start"); }, sessions, "",
                value -> value,
                ping -> new IdleKeepalive(Duration.ofDays(1), ping, new NoScheduler()),
                false, now::get);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (controller) {
            var result = executor.submit(controller::run);
            assertTrue(terminal.firstRead.await(2, TimeUnit.SECONDS));
            controller.interrupt();
            assertTrue(terminal.secondRead.await(2, TimeUnit.SECONDS));
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            controller.interrupt();

            assertEquals(ExitCodes.OK, (int) result.get(2, TimeUnit.SECONDS));
            assertEquals(2, terminal.reads.get());
            assertTrue(terminal.output.toString().contains("^C"));
        } finally {
            executor.shutdownNow();
        }
    }

    private ShellController controller(ShellTerminal terminal, StreamingAgentModel model,
            ShellToolSession tools, String directory) throws Exception {
        Path root = temporary.newFolder(directory).toPath();
        SessionStore sessions = new SessionStore(root, value -> value, warning -> { });
        ShellOptions options = new ShellOptions(ShellOptions.Mode.CONNECTED, "instance", null,
                null, false, null, null, null, null, false, 4, 30, null);
        ShellEnvironment environment = new ShellEnvironment("connected", "provider", "model",
                "http://localhost/mcp", "instance", model, tools, () -> { });
        return new ShellController(terminal, options, ignored -> environment, sessions, "", value -> value,
                ping -> new IdleKeepalive(Duration.ofDays(1), ping, new NoScheduler()));
    }

    private static final class AsyncToolCallingModel implements StreamingAgentModel {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<AgentMessage.Assistant> pending = new CompletableFuture<>();
        @Override public CompletionStage<AgentMessage.Assistant> complete(AgentModel.Request request,
                CancellationToken cancellation, StreamObserver observer) {
            started.countDown();
            return pending;
        }
        void completeWithToolCall() {
            pending.complete(AgentMessage.Assistant.tools(List.of(
                    new ToolCall("approval-call", "write_file", "{}"))));
        }
    }

    private static final class ApprovalTools implements ShellToolSession {
        private final AtomicBoolean executed = new AtomicBoolean();
        private final ToolDefinition definition = new ToolDefinition("write_file", "", schema());
        @Override public ToolRuntime runtime() {
            return new ToolRuntime() {
                @Override public List<ToolDefinition> tools() { return List.of(definition); }
                @Override public CompletionStage<ToolExecutionResult> execute(String name,
                        JsonObject arguments, CancellationToken cancellation) {
                    executed.set(true);
                    return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
                }
            };
        }
        @Override public CompletionStage<List<ToolDefinition>> refresh() {
            return CompletableFuture.completedFuture(List.of(definition));
        }
        @Override public CompletionStage<Void> ping() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<ShellToolSession> reinitialize() {
            return CompletableFuture.completedFuture(this);
        }
        @Override public boolean isExpired(Throwable failure) { return false; }
        @Override public void close() { }
    }

    private static final class BlockingApprovalTerminal implements ShellTerminal {
        private final StringBuilder output = new StringBuilder();
        private final CountDownLatch approvalStarted = new CountDownLatch(1);
        private final CountDownLatch releaseApproval = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger activeReaders = new AtomicInteger();
        private final AtomicInteger maximumReaders = new AtomicInteger();
        private final AtomicInteger aborts = new AtomicInteger();
        private final AtomicBoolean approvalExited = new AtomicBoolean();
        @Override public String readLine(String prompt) {
            int active = activeReaders.incrementAndGet();
            maximumReaders.accumulateAndGet(active, Math::max);
            int call = calls.incrementAndGet();
            synchronized (output) { output.append(prompt); }
            try {
                if (call == 1) return "run tool";
                if (call == 2) {
                    approvalStarted.countDown();
                    try {
                        assertTrue(releaseApproval.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    } finally {
                        approvalExited.set(true);
                    }
                    return "y";
                }
                if (call == 3) return "/exit";
                return null;
            } finally {
                activeReaders.decrementAndGet();
            }
        }
        @Override public void abortRead() {
            aborts.incrementAndGet();
            releaseApproval.countDown();
        }
        @Override public void println(String text) { synchronized (output) { output.append(text).append('\n'); } }
        @Override public void flush() { }
        @Override public void close() { releaseApproval.countDown(); }
    }

    private static final class PromptInterruptTerminal implements ShellTerminal {
        private final String[] partialLines;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicReference<CountDownLatch> active = new AtomicReference<>();
        private final CountDownLatch firstRead = new CountDownLatch(1);
        private final CountDownLatch secondRead = new CountDownLatch(1);
        private final StringBuilder output = new StringBuilder();
        PromptInterruptTerminal(String... partialLines) { this.partialLines = partialLines; }
        @Override public String readLine(String prompt) {
            int index = reads.getAndIncrement();
            CountDownLatch release = new CountDownLatch(1);
            active.set(release);
            synchronized (output) { output.append(prompt); }
            if (index == 0) firstRead.countDown(); else secondRead.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } finally {
                active.compareAndSet(release, null);
            }
            throw new TerminalInterruptedException(partialLines[index]);
        }
        @Override public void abortRead() {
            CountDownLatch release = active.get();
            if (release != null) release.countDown();
        }
        @Override public void println(String text) { synchronized (output) { output.append(text).append('\n'); } }
        @Override public void flush() { }
        @Override public void close() { abortRead(); }
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
