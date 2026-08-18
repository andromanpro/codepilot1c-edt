/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;

    public AgentRuntime(AgentModel model, ToolRuntime tools, AgentRunConfig config, LogSink logSink) {
        this.model = Objects.requireNonNull(model, "model"); //$NON-NLS-1$
        this.tools = Objects.requireNonNull(tools, "tools"); //$NON-NLS-1$
        this.config = Objects.requireNonNull(config, "config"); //$NON-NLS-1$
        this.logSink = logSink == null ? NO_LOG : logSink;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "codepilot-agent-timeout"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
    }

    public AgentRuntime(AgentModel model, ToolRuntime tools, AgentRunConfig config) {
        this(model, tools, config, null);
    }

    public CompletableFuture<AgentResult> run(AgentRequest request) {
        return run(request, CancellationToken.none());
    }

    public CompletableFuture<AgentResult> run(AgentRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        Objects.requireNonNull(cancellation, "cancellation"); //$NON-NLS-1$
        if (closed) throw new IllegalStateException("agent runtime is closed"); //$NON-NLS-1$
        RunContext context = new RunContext(request, cancellation);
        context.start();
        return context.result;
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }

    private final class RunContext {
        private final AgentRequest request;
        private final CancellationToken externalCancellation;
        private final CancellationSource cancellation = new CancellationSource();
        private final ArrayList<AgentMessage> transcript;
        private final CompletableFuture<AgentResult> result = new CompletableFuture<>();
        private int steps;
        private Set<String> availableTools = Set.of();
        private ScheduledFuture<?> timeoutTask;
        private CancellationToken.Registration externalRegistration;

        RunContext(AgentRequest request, CancellationToken externalCancellation) {
            this.request = request;
            this.externalCancellation = externalCancellation;
            this.transcript = new ArrayList<>(request.messages());
        }

        void start() {
            timeoutTask = scheduler.schedule(() -> {
                cancellation.cancel();
                finish(AgentResult.Status.TIMED_OUT, null,
                        new AgentError(AgentError.Code.TIMEOUT, "Agent run timed out")); //$NON-NLS-1$
            }, config.timeout().toNanos(), TimeUnit.NANOSECONDS);
            externalRegistration = externalCancellation.onCancel(() -> {
                cancellation.cancel();
                finish(AgentResult.Status.CANCELLED, null,
                        new AgentError(AgentError.Code.CANCELLED, "Agent run was cancelled")); //$NON-NLS-1$
            });
            result.whenComplete((ignored, failure) -> cleanup());
            if (!result.isDone()) {
                log(Level.INFO, "run started; maxSteps=" + config.maxSteps()); //$NON-NLS-1$
                nextStep();
            }
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
                        .map(ToolDefinition::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                modelRequest = new AgentModel.Request(List.copyOf(transcript), definitions);
            }
            log(Level.DEBUG, "model step=" + steps + "; tools=" + definitions.size()); //$NON-NLS-1$ //$NON-NLS-2$

            CompletionStage<AgentMessage.Assistant> stage;
            try {
                stage = Objects.requireNonNull(model.complete(modelRequest, cancellation),
                        "model completion stage"); //$NON-NLS-1$
            } catch (RuntimeException failure) {
                providerFailure(failure);
                return;
            }
            observe(stage, this::acceptAssistant, this::providerFailure);
        }

        void acceptAssistant(AgentMessage.Assistant assistant) {
            if (result.isDone() || cancellation.isCancelled()) return;
            if (assistant == null) {
                providerFailure(new AgentModelException(AgentModelException.Kind.MALFORMED_RESPONSE,
                        "Provider returned no assistant message")); //$NON-NLS-1$
                return;
            }
            if (hasDuplicateCallIds(assistant.toolCalls())) {
                providerFailure(new AgentModelException(AgentModelException.Kind.MALFORMED_RESPONSE,
                        "Provider returned duplicate tool-call ids")); //$NON-NLS-1$
                return;
            }
            synchronized (this) {
                transcript.add(assistant);
            }
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
            JsonObject arguments = parseArguments(call.argumentsJson());
            if (arguments == null) {
                appendTool(call, ToolExecutionResult.failure(
                        "MALFORMED_ARGUMENTS", "Tool arguments must be a JSON object")); //$NON-NLS-1$ //$NON-NLS-2$
                executeCall(calls, index + 1);
                return;
            }
            if (!toolExists(call.name())) {
                appendTool(call, ToolExecutionResult.failure("UNKNOWN_TOOL", "Requested tool is not available")); //$NON-NLS-1$ //$NON-NLS-2$
                executeCall(calls, index + 1);
                return;
            }

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
            log(toolResult.error() ? Level.WARN : Level.DEBUG,
                    "tool result; error=" + toolResult.error()); //$NON-NLS-1$
        }

        boolean toolExists(String name) {
            synchronized (this) {
                return availableTools.contains(name);
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
            }
            CompletableFuture<?> delegate = cancellable;
            stage.whenComplete((value, thrown) -> {
                if (thrown == null) future.complete(value);
                else future.completeExceptionally(thrown);
            });
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
                        code = AgentError.Code.PROVIDER_HTTP;
                        message = "Provider returned an HTTP error"; //$NON-NLS-1$
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
            final List<AgentMessage> snapshot;
            final int completedSteps;
            synchronized (this) {
                snapshot = List.copyOf(transcript);
                completedSteps = steps;
            }
            AgentResult terminal = new AgentResult(status, Optional.ofNullable(text), snapshot,
                    completedSteps, Optional.ofNullable(error));
            if (result.complete(terminal)) {
                log(status == AgentResult.Status.COMPLETED ? Level.INFO : Level.WARN,
                        "run finished; status=" + status + "; steps=" + completedSteps); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        void cleanup() {
            if (timeoutTask != null) timeoutTask.cancel(false);
            if (externalRegistration != null) externalRegistration.close();
        }

        void log(Level level, String message) {
            try {
                logSink.log(Event.message(level, LOG_CATEGORY,
                        "opId=" + request.operationId() + "; " + message)); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (RuntimeException ignored) {
                // Logging is isolated from control flow and no failure cause is re-logged.
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

    private boolean hasDuplicateCallIds(List<ToolCall> calls) {
        Set<String> ids = new HashSet<>();
        return calls.stream().anyMatch(call -> !ids.add(call.id()));
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
