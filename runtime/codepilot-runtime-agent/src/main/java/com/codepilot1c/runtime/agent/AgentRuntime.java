/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.codepilot1c.runtime.spi.LogSink;
import com.codepilot1c.runtime.spi.LogSink.Event;
import com.codepilot1c.runtime.spi.LogSink.Level;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Plain-Java bounded agent/tool loop.
 *
 * <p>Each step is one model completion followed by all tool calls in that
 * completion. Tool failures are returned to the model as structured tool
 * messages. Provider failures, cancellation, timeout, and the step bound are
 * terminal typed results. Logs contain only operation metadata and counters,
 * never message content, arguments, tool results, response bodies, or causes.</p>
 */
public final class AgentRuntime implements AutoCloseable {
    private static final String LOG_CATEGORY = "agent-runtime"; //$NON-NLS-1$
    private static final LogSink NO_LOG = event -> { };

    private final AgentModel model;
    private final ToolRuntime tools;
    private final AgentRunConfig config;
    private final LogSink logSink;
    private final AgentEventListener eventListener;
    private final ToolApprover approver;
    private final ScheduledExecutorService scheduler;
    private final Object lifecycleLock = new Object();
    private final Set<RunContext> activeRuns = new HashSet<>();
    private boolean closed;

    public AgentRuntime(AgentModel model, ToolRuntime tools, AgentRunConfig config, LogSink logSink,
            AgentEventListener eventListener, ToolApprover approver) {
        this.model = Objects.requireNonNull(model, "model"); //$NON-NLS-1$
        this.tools = Objects.requireNonNull(tools, "tools"); //$NON-NLS-1$
        this.config = Objects.requireNonNull(config, "config"); //$NON-NLS-1$
        this.logSink = logSink == null ? NO_LOG : logSink;
        this.eventListener = eventListener == null ? AgentEventListener.NOOP : eventListener;
        this.approver = approver == null ? ToolApprover.ALLOW : approver;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "codepilot-agent-timeout"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
    }

    public AgentRuntime(AgentModel model, ToolRuntime tools, AgentRunConfig config,
            AgentEventListener eventListener, ToolApprover approver) {
        this(model, tools, config, null, eventListener, approver);
    }

    public AgentRuntime(AgentModel model, ToolRuntime tools, AgentRunConfig config, LogSink logSink) {
        this(model, tools, config, logSink, AgentEventListener.NOOP, ToolApprover.ALLOW);
    }

    public AgentRuntime(AgentModel model, ToolRuntime tools, AgentRunConfig config) {
        this(model, tools, config, null, AgentEventListener.NOOP, ToolApprover.ALLOW);
    }

    /**
     * Starts a run and returns a runtime-owned completion handle.
     * External completion/timeout mutation is rejected; {@link CompletableFuture#cancel(boolean)}
     * remains supported and propagates into the active provider or tool request.
     */
    public CompletableFuture<AgentResult> run(AgentRequest request) {
        return run(request, CancellationToken.none());
    }

    /**
     * Starts a run with host cancellation and returns a runtime-owned completion handle.
     * External completion/timeout mutation is rejected; cancellation remains supported.
     */
    public CompletableFuture<AgentResult> run(AgentRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        Objects.requireNonNull(cancellation, "cancellation"); //$NON-NLS-1$
        RunContext context;
        AgentResult rejected = null;
        synchronized (lifecycleLock) {
            if (closed) {
                context = null;
                rejected = closedResult(request);
            } else {
                context = new RunContext(request, cancellation);
                activeRuns.add(context);
                context.arm();
            }
        }
        if (rejected != null) {
            try {
                eventListener.onTurnFinished(request.operationId(), rejected);
            } catch (RuntimeException ignored) {
                // Observation hooks cannot alter admission results.
            }
            return completedHandle(rejected);
        }
        context.begin();
        return context.result;
    }

    @Override
    public void close() {
        List<RunContext> runs;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            runs = List.copyOf(activeRuns);
        }
        for (RunContext run : runs) run.closeByRuntime();
        scheduler.shutdownNow();
    }

    int activeRunCount() {
        synchronized (lifecycleLock) {
            return activeRuns.size();
        }
    }

    private final class RunContext {
        private final AgentRequest request;
        private final CancellationToken externalCancellation;
        private final CancellationSource cancellation = new CancellationSource();
        private final ArrayList<AgentMessage> transcript;
        private final RunFuture result = new RunFuture();
        private final Set<String> seenToolCallIds = new HashSet<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicReference<Thread> terminalOwner = new AtomicReference<>();
        private final CountDownLatch terminalPublished = new CountDownLatch(1);
        private final Object eventLock = new Object();
        private int steps;
        private Map<String, ToolDefinition> availableTools = Map.of();
        private ScheduledFuture<?> timeoutTask;
        private CancellationToken.Registration externalRegistration;

        RunContext(AgentRequest request, CancellationToken externalCancellation) {
            this.request = request;
            this.externalCancellation = externalCancellation;
            this.transcript = new ArrayList<>(request.messages());
            result.onCancel(this::cancelFromCaller);
        }

        void arm() {
            timeoutTask = scheduler.schedule(() -> {
                cancellation.cancel();
                finish(AgentResult.Status.TIMED_OUT, null,
                        new AgentError(AgentError.Code.TIMEOUT, "Agent run timed out")); //$NON-NLS-1$
            }, config.timeout().toNanos(), TimeUnit.NANOSECONDS);
            CancellationToken.Registration registration = externalCancellation.onCancel(() -> {
                cancellation.cancel();
                finish(AgentResult.Status.CANCELLED, null,
                        new AgentError(AgentError.Code.CANCELLED, "Agent run was cancelled")); //$NON-NLS-1$
            });
            externalRegistration = registration;
            if (terminal.get()) registration.close();
        }

        void begin() {
            if (!result.isDone()) {
                log(Level.INFO, "run started; maxSteps=" + config.maxSteps()); //$NON-NLS-1$
                nextStep();
            }
        }

        boolean cancelFromCaller(boolean mayInterruptIfRunning) {
            if (!terminal.compareAndSet(false, true)) return false;
            terminalOwner.set(Thread.currentThread());
            cancellation.cancel();
            AgentResult terminalResult = terminalResult(AgentResult.Status.CANCELLED, null,
                    new AgentError(AgentError.Code.CANCELLED, "Agent run was cancelled")); //$NON-NLS-1$
            try {
                cleanupAndRemove();
                publishTurnFinished(terminalResult);
                return result.cancelDirect(mayInterruptIfRunning);
            } finally {
                terminalOwner.set(null);
                terminalPublished.countDown();
            }
        }

        void closeByRuntime() {
            cancellation.cancel();
            finish(AgentResult.Status.CANCELLED, null,
                    new AgentError(AgentError.Code.CLOSED, "Agent runtime was closed")); //$NON-NLS-1$
            awaitTerminalPublication();
        }

        void nextStep() {
            if (result.isDone() || cancellation.isCancelled()) return;
            if (steps >= config.maxSteps()) {
                finish(AgentResult.Status.STEP_LIMIT, null,
                        new AgentError(AgentError.Code.STEP_LIMIT, "Agent step limit reached")); //$NON-NLS-1$
                return;
            }

            final List<ToolDefinition> definitions;
            try {
                definitions = validateCatalog(tools.tools());
            } catch (RuntimeException failure) {
                finish(AgentResult.Status.FAILED, null,
                        new AgentError(AgentError.Code.TOOL_CATALOG, "Tool catalog is invalid")); //$NON-NLS-1$
                return;
            }

            final AgentModel.Request modelRequest;
            synchronized (this) {
                steps++;
                availableTools = definitions.stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                ToolDefinition::name, definition -> definition));
                modelRequest = new AgentModel.Request(List.copyOf(transcript), definitions);
            }
            publishStepStarted();
            if (result.isDone() || cancellation.isCancelled()) return;
            log(Level.DEBUG, "model step=" + steps + "; tools=" + definitions.size()); //$NON-NLS-1$ //$NON-NLS-2$

            CompletionStage<AgentMessage.Assistant> stage;
            StepStreamObserver streamObserver = null;
            try {
                if (model instanceof StreamingAgentModel streaming) {
                    streamObserver = new StepStreamObserver(steps);
                    stage = Objects.requireNonNull(
                            streaming.complete(modelRequest, cancellation, streamObserver),
                            "model completion stage"); //$NON-NLS-1$
                } else {
                    stage = Objects.requireNonNull(model.complete(modelRequest, cancellation),
                            "model completion stage"); //$NON-NLS-1$
                }
            } catch (RuntimeException failure) {
                if (streamObserver != null) streamObserver.close();
                providerFailure(failure);
                return;
            }
            StepStreamObserver observer = streamObserver;
            observe(stage, assistant -> {
                if (observer != null) observer.close();
                acceptAssistant(assistant);
            }, failure -> {
                if (observer != null) observer.close();
                providerFailure(failure);
            });
        }

        void acceptAssistant(AgentMessage.Assistant assistant) {
            if (result.isDone() || cancellation.isCancelled()) return;
            if (assistant == null) {
                providerFailure(new AgentModelException(AgentModelException.Kind.MALFORMED_RESPONSE,
                        "Provider returned no assistant message")); //$NON-NLS-1$
                return;
            }
            if (!reserveToolCallIds(assistant.toolCalls())) {
                providerFailure(new AgentModelException(AgentModelException.Kind.MALFORMED_RESPONSE,
                        "Provider returned duplicate tool-call ids")); //$NON-NLS-1$
                return;
            }
            synchronized (this) {
                transcript.add(assistant);
            }
            publishAssistantMessage(assistant);
            if (result.isDone() || cancellation.isCancelled()) return;
            if (assistant.toolCalls().isEmpty()) {
                finish(AgentResult.Status.COMPLETED, assistant.text().orElse(""), null); //$NON-NLS-1$
                return;
            }
            executeCall(assistant.toolCalls(), 0);
        }

        void executeCall(List<ToolCall> calls, int index) {
            if (result.isDone() || cancellation.isCancelled()) return;
            if (index >= calls.size()) {
                nextStep();
                return;
            }
            ToolCall call = calls.get(index);
            publishToolCallStarted(call);
            if (result.isDone() || cancellation.isCancelled()) return;
            JsonObject arguments = parseArguments(call.argumentsJson());
            if (arguments == null) {
                appendTool(call, ToolExecutionResult.failure(
                        "MALFORMED_ARGUMENTS", "Tool arguments must be a JSON object")); //$NON-NLS-1$ //$NON-NLS-2$
                executeCall(calls, index + 1);
                return;
            }
            ToolDefinition definition = toolDefinition(call.name());
            if (definition == null) {
                appendTool(call, ToolExecutionResult.failure("UNKNOWN_TOOL", "Requested tool is not available")); //$NON-NLS-1$ //$NON-NLS-2$
                executeCall(calls, index + 1);
                return;
            }

            CompletionStage<ToolApprover.Decision> approval;
            try {
                approval = Objects.requireNonNull(
                        approver.approve(call, definition, cancellation),
                        "tool approval stage"); //$NON-NLS-1$
            } catch (RuntimeException failure) {
                approvalFailure(failure);
                return;
            }
            observe(approval, decision -> acceptApproval(call, arguments,
                    calls, index, decision), this::approvalFailure);
        }

        void acceptApproval(ToolCall call, JsonObject arguments,
                List<ToolCall> calls, int index, ToolApprover.Decision decision) {
            if (result.isDone() || cancellation.isCancelled()) return;
            if (decision == null) {
                approvalFailure(new IllegalStateException("Tool approver returned no decision")); //$NON-NLS-1$
                return;
            }
            if (!decision.allowed()) {
                appendTool(call, ToolExecutionResult.failure(
                        "CONFIRMATION_DENIED", decision.reason())); //$NON-NLS-1$
                executeCall(calls, index + 1);
                return;
            }
            executeApproved(call, arguments, calls, index);
        }

        void executeApproved(ToolCall call, JsonObject arguments, List<ToolCall> calls, int index) {
            CompletionStage<ToolExecutionResult> stage;
            try {
                stage = Objects.requireNonNull(tools.execute(call.name(), arguments, cancellation),
                        "tool completion stage"); //$NON-NLS-1$
            } catch (RuntimeException failure) {
                toolFailure(call, calls, index);
                return;
            }
            observe(stage, toolResult -> {
                appendTool(call, toolResult == null
                        ? ToolExecutionResult.failure("TOOL_EXECUTION_FAILED", "Tool returned no result") //$NON-NLS-1$ //$NON-NLS-2$
                        : toolResult);
                executeCall(calls, index + 1);
            }, failure -> toolFailure(call, calls, index));
        }

        void approvalFailure(Throwable failure) {
            if (result.isDone()) return;
            if (failure instanceof CancellationException && cancellation.isCancelled()) return;
            finish(AgentResult.Status.FAILED, null,
                    new AgentError(AgentError.Code.TOOL_APPROVAL, "Tool approval failed")); //$NON-NLS-1$
        }

        void toolFailure(ToolCall call, List<ToolCall> calls, int index) {
            if (result.isDone()) return;
            appendTool(call, ToolExecutionResult.failure(
                    "TOOL_EXECUTION_FAILED", "Tool execution failed")); //$NON-NLS-1$ //$NON-NLS-2$
            executeCall(calls, index + 1);
        }

        void appendTool(ToolCall call, ToolExecutionResult toolResult) {
            synchronized (this) {
                transcript.add(new AgentMessage.Tool(call.id(), call.name(), toolResult));
            }
            publishToolCallResult(call, toolResult);
            log(toolResult.error() ? Level.WARN : Level.DEBUG,
                    "tool result; error=" + toolResult.error()); //$NON-NLS-1$
        }

        ToolDefinition toolDefinition(String name) {
            synchronized (this) {
                return availableTools.get(name);
            }
        }

        boolean reserveToolCallIds(List<ToolCall> calls) {
            Set<String> newIds = new HashSet<>();
            synchronized (this) {
                for (ToolCall call : calls) {
                    if (seenToolCallIds.contains(call.id()) || !newIds.add(call.id())) return false;
                }
                seenToolCallIds.addAll(newIds);
                return true;
            }
        }

        <T> void observe(CompletionStage<T> stage,
                java.util.function.Consumer<T> success,
                java.util.function.Consumer<Throwable> failure) {
            CompletableFuture<T> future = new CompletableFuture<>();
            CompletableFuture<?> cancellable;
            try {
                cancellable = stage.toCompletableFuture();
            } catch (UnsupportedOperationException unsupported) {
                cancellable = null;
            } catch (RuntimeException registrationFailure) {
                failure.accept(registrationFailure);
                return;
            }
            CompletableFuture<?> delegate = cancellable;
            try {
                stage.whenComplete((value, thrown) -> {
                    if (thrown == null) future.complete(value);
                    else future.completeExceptionally(thrown);
                });
            } catch (RuntimeException registrationFailure) {
                if (!result.isDone()) failure.accept(registrationFailure);
                return;
            }
            CancellationToken.Registration registration = cancellation.onCancel(() -> {
                if (delegate != null) delegate.cancel(true);
                future.cancel(true);
            });
            future.whenComplete((value, thrown) -> {
                registration.close();
                if (result.isDone()) return;
                if (thrown == null) success.accept(value);
                else {
                    Throwable unwrapped = unwrap(thrown);
                    if (unwrapped instanceof CancellationException && cancellation.isCancelled()) return;
                    failure.accept(unwrapped);
                }
            });
        }

        void providerFailure(Throwable failure) {
            if (result.isDone()) return;
            if (failure instanceof CancellationException && cancellation.isCancelled()) return;
            AgentError.Code code = AgentError.Code.PROVIDER_TRANSPORT;
            String message = "Provider request failed"; //$NON-NLS-1$
            if (failure instanceof AgentModelException modelFailure) {
                switch (modelFailure.kind()) {
                    case HTTP:
                        boolean authenticationFailed = modelFailure.httpStatus() == 401
                                || modelFailure.httpStatus() == 403;
                        code = authenticationFailed ? AgentError.Code.PROVIDER_AUTH
                                : AgentError.Code.PROVIDER_HTTP;
                        message = authenticationFailed ? "Provider authentication failed" //$NON-NLS-1$
                                : "Provider returned an HTTP error"; //$NON-NLS-1$
                        break;
                    case MALFORMED_RESPONSE:
                        code = AgentError.Code.PROVIDER_RESPONSE;
                        message = "Provider response is malformed"; //$NON-NLS-1$
                        break;
                    case TRANSPORT:
                        break;
                    default:
                        break;
                }
            }
            finish(AgentResult.Status.FAILED, null, new AgentError(code, message));
        }

        void finish(AgentResult.Status status, String text, AgentError error) {
            if (!terminal.compareAndSet(false, true)) return;
            terminalOwner.set(Thread.currentThread());
            AgentResult terminalResult = terminalResult(status, text, error);
            try {
                cleanupAndRemove();
                publishTurnFinished(terminalResult);
                result.completeInternal(terminalResult);
            } finally {
                terminalOwner.set(null);
                terminalPublished.countDown();
            }
            log(status == AgentResult.Status.COMPLETED ? Level.INFO : Level.WARN,
                    "run finished; status=" + status + "; steps=" + terminalResult.steps()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        AgentResult terminalResult(AgentResult.Status status, String text, AgentError error) {
            synchronized (this) {
                return new AgentResult(status, Optional.ofNullable(text), List.copyOf(transcript),
                        steps, Optional.ofNullable(error));
            }
        }

        void publishStepStarted() {
            publishActiveEvent(() -> eventListener.onStepStarted(request.operationId(), steps));
        }

        void publishAssistantMessage(AgentMessage.Assistant assistant) {
            publishActiveEvent(() -> eventListener.onAssistantMessage(
                    request.operationId(), steps, assistant));
        }

        void publishToolCallStarted(ToolCall call) {
            publishActiveEvent(() -> eventListener.onToolCallStarted(request.operationId(), steps, call));
        }

        void publishToolCallResult(ToolCall call, ToolExecutionResult toolResult) {
            publishActiveEvent(() -> eventListener.onToolCallResult(
                    request.operationId(), steps, call, toolResult));
        }

        void publishTurnFinished(AgentResult terminalResult) {
            publishEvent(() -> eventListener.onTurnFinished(request.operationId(), terminalResult));
        }

        void publishEvent(Runnable event) {
            synchronized (eventLock) {
                try {
                    event.run();
                } catch (RuntimeException ignored) {
                    // Observation hooks cannot alter the agent loop.
                }
            }
        }

        void publishActiveEvent(Runnable event) {
            synchronized (eventLock) {
                if (terminal.get()) return;
                try {
                    event.run();
                } catch (RuntimeException ignored) {
                    // Observation hooks cannot alter the agent loop.
                }
            }
        }

        void cleanupAndRemove() {
            if (timeoutTask != null) timeoutTask.cancel(false);
            try {
                if (externalRegistration != null) externalRegistration.close();
            } catch (RuntimeException ignored) {
                // Host cancellation callback cleanup cannot block terminal publication.
            } finally {
                synchronized (lifecycleLock) {
                    activeRuns.remove(this);
                }
            }
        }

        void awaitTerminalPublication() {
            if (terminalOwner.get() == Thread.currentThread()) return;
            boolean interrupted = false;
            while (terminalPublished.getCount() != 0) {
                try {
                    terminalPublished.await();
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        void log(Level level, String message) {
            try {
                logSink.log(Event.message(level, LOG_CATEGORY,
                        "opId=" + request.operationId() + "; " + message)); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (RuntimeException ignored) {
                // Logging is isolated from control flow and no failure cause is re-logged.
            }
        }

        private final class StepStreamObserver implements StreamObserver {
            private final int observedStep;
            private boolean open = true;

            StepStreamObserver(int observedStep) {
                this.observedStep = observedStep;
            }

            @Override
            public void onTextDelta(String delta) {
                publishDelta(() -> eventListener.onAssistantTextDelta(
                        request.operationId(), observedStep, delta));
            }

            @Override
            public void onReasoningDelta(String delta) {
                publishDelta(() -> eventListener.onAssistantReasoningDelta(
                        request.operationId(), observedStep, delta));
            }

            void close() {
                synchronized (eventLock) {
                    open = false;
                }
            }

            private void publishDelta(Runnable event) {
                synchronized (eventLock) {
                    if (!open || terminal.get()) return;
                    try {
                        event.run();
                    } catch (RuntimeException ignored) {
                        // Observation hooks cannot alter the model completion.
                    }
                }
            }
        }
    }

    private List<ToolDefinition> validateCatalog(List<ToolDefinition> definitions) {
        Objects.requireNonNull(definitions, "tool definitions"); //$NON-NLS-1$
        List<ToolDefinition> copy = List.copyOf(definitions);
        Set<String> names = new HashSet<>();
        for (ToolDefinition definition : copy) {
            if (definition == null || !names.add(definition.name())) {
                throw new IllegalArgumentException("tool names must be unique"); //$NON-NLS-1$
            }
        }
        return copy;
    }

    private JsonObject parseArguments(String value) {
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException failure) {
            return null;
        }
    }

    private AgentResult closedResult(AgentRequest request) {
        return new AgentResult(AgentResult.Status.CANCELLED, Optional.empty(), request.messages(), 0,
                Optional.of(new AgentError(AgentError.Code.CLOSED, "Agent runtime is closed"))); //$NON-NLS-1$
    }

    private CompletableFuture<AgentResult> completedHandle(AgentResult value) {
        RunFuture future = new RunFuture();
        future.completeInternal(value);
        return future;
    }

    private static final class RunFuture extends CompletableFuture<AgentResult> {
        private volatile java.util.function.Predicate<Boolean> cancelAction = ignored -> false;

        void onCancel(java.util.function.Predicate<Boolean> action) {
            cancelAction = Objects.requireNonNull(action, "action"); //$NON-NLS-1$
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelAction.test(mayInterruptIfRunning);
        }

        @Override
        public boolean complete(AgentResult value) {
            return false;
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            return false;
        }

        @Override
        public void obtrudeValue(AgentResult value) {
            throw externalMutation();
        }

        @Override
        public void obtrudeException(Throwable failure) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<AgentResult> completeAsync(
                Supplier<? extends AgentResult> supplier) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<AgentResult> completeAsync(
                Supplier<? extends AgentResult> supplier, java.util.concurrent.Executor executor) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<AgentResult> orTimeout(long timeout, TimeUnit unit) {
            throw externalMutation();
        }

        @Override
        public CompletableFuture<AgentResult> completeOnTimeout(
                AgentResult value, long timeout, TimeUnit unit) {
            throw externalMutation();
        }

        boolean cancelDirect(boolean mayInterruptIfRunning) {
            return super.cancel(mayInterruptIfRunning);
        }

        boolean completeInternal(AgentResult value) {
            return super.complete(value);
        }

        private UnsupportedOperationException externalMutation() {
            return new UnsupportedOperationException(
                    "Agent run completion is owned by AgentRuntime; use cancel() to stop it"); //$NON-NLS-1$
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while ((value instanceof CompletionException
                || value instanceof java.util.concurrent.ExecutionException)
                && value.getCause() != null) {
            value = value.getCause();
        }
        return value;
    }
}
