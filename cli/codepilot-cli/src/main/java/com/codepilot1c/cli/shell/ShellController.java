/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.shell.ModeResolver.ModeResolutionException;
import com.codepilot1c.cli.shell.render.RenderConfig;
import com.codepilot1c.cli.shell.render.StreamingTextSink;
import com.codepilot1c.cli.shell.render.TerminalRenderer;
import com.codepilot1c.cli.shell.render.ToolCallPresentation;
import com.codepilot1c.cli.shell.render.ToolResultPresentation;
import com.codepilot1c.cli.shell.session.ResumedSession;
import com.codepilot1c.cli.shell.session.SessionContext;
import com.codepilot1c.cli.shell.session.SessionMetadata;
import com.codepilot1c.cli.shell.session.SessionStore;
import com.codepilot1c.runtime.agent.AgentError;
import com.codepilot1c.runtime.agent.AgentEventListener;
import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentResult;
import com.codepilot1c.runtime.agent.CancellationSource;
import com.codepilot1c.runtime.agent.ToolCall;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolExecutionResult;

/** Interactive, multi-turn shell state machine. */
public final class ShellController implements AutoCloseable, SlashCommandDispatcher.Commands {
    private static final long SECOND_INTERRUPT_WINDOW_NANOS = Duration.ofSeconds(2).toNanos();
    private static final String HELP = "Commands: /help, /exit, /new, /status, /tools, /model, "
            + "/sessions, /resume <id>";
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final ShellTerminal terminal;
    private final TerminalReader terminalReader;
    private final ShellOptions options;
    private final EnvironmentProvider environments;
    private final SessionStore sessions;
    private final SystemPromptProvider systemPrompt;
    private final UnaryOperator<String> redactor;
    private final SlashCommandDispatcher slash = new SlashCommandDispatcher();
    private final ConfirmationPrompter prompter;
    private final KeepaliveFactory keepaliveFactory;
    private final boolean noColor;
    private final LongSupplier nanoTime;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean exit = new AtomicBoolean();
    private final AtomicLong lastInterruptNanos = new AtomicLong();
    private final List<AgentMessage> history = new ArrayList<>();
    private volatile CancellationSource activeCancellation;
    private ShellEnvironment environment;
    private TurnRunner turns;
    private IdleKeepalive keepalive;
    private SessionMetadata session;

    public ShellController(ShellTerminal terminal, ShellOptions options,
            EnvironmentProvider environments, SessionStore sessions,
            String systemPrompt, UnaryOperator<String> redactor) {
        this(terminal, options, environments, sessions, systemPrompt, redactor,
                IdleKeepalive::new, false, System::nanoTime);
    }

    public ShellController(ShellTerminal terminal, ShellOptions options,
            EnvironmentProvider environments, SessionStore sessions, String systemPrompt,
            UnaryOperator<String> redactor, KeepaliveFactory keepaliveFactory) {
        this(terminal, options, environments, sessions,
                () -> systemPrompt == null ? "" : systemPrompt, redactor,
                keepaliveFactory, false, System::nanoTime);
    }

    ShellController(ShellTerminal terminal, ShellOptions options,
            EnvironmentProvider environments, SessionStore sessions, String systemPrompt,
            UnaryOperator<String> redactor, KeepaliveFactory keepaliveFactory,
            boolean noColor, LongSupplier nanoTime) {
        this(terminal, options, environments, sessions,
                () -> systemPrompt == null ? "" : systemPrompt, redactor,
                keepaliveFactory, noColor, nanoTime);
    }

    public ShellController(ShellTerminal terminal, ShellOptions options,
            EnvironmentProvider environments, SessionStore sessions,
            SystemPromptProvider systemPrompt, UnaryOperator<String> redactor,
            boolean noColor) {
        this(terminal, options, environments, sessions, systemPrompt, redactor,
                IdleKeepalive::new, noColor, System::nanoTime);
    }

    private ShellController(ShellTerminal terminal, ShellOptions options,
            EnvironmentProvider environments, SessionStore sessions,
            SystemPromptProvider systemPrompt, UnaryOperator<String> redactor,
            KeepaliveFactory keepaliveFactory, boolean noColor, LongSupplier nanoTime) {
        this.terminal = java.util.Objects.requireNonNull(terminal, "terminal");
        this.terminalReader = new TerminalReader(terminal);
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.environments = java.util.Objects.requireNonNull(environments, "environments");
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.systemPrompt = java.util.Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.redactor = java.util.Objects.requireNonNull(redactor, "redactor");
        this.keepaliveFactory = java.util.Objects.requireNonNull(keepaliveFactory, "keepaliveFactory");
        this.noColor = noColor;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.prompter = new ConfirmationPrompter(terminal, terminalReader, redactor);
    }

    public ShellController(ShellTerminal terminal, ShellOptions options,
            EnvironmentProvider environments, SessionStore sessions,
            SystemPromptProvider systemPrompt, UnaryOperator<String> redactor) {
        this(terminal, options, environments, sessions, systemPrompt, redactor,
                IdleKeepalive::new, false, System::nanoTime);
    }

    public int run() {
        terminal.println("CodePilot shell (foundation)");
        terminal.println("Type /help for commands or /exit to leave.");
        terminal.flush();
        while (!exit.get()) {
            String input;
            try { input = terminalReader.readLine("codepilot> "); }
            catch (TerminalInterruptedException interrupt) {
                if (handlePromptInterrupt(interrupt.partialLine())) break;
                continue;
            }
            catch (RuntimeException failure) {
                if (exit.get()) break;
                throw failure;
            }
            if (input == null) break;
            lastInterruptNanos.set(0);
            if (input.isBlank()) continue;
            try {
                if (!slash.dispatch(input, this)) runTurn(input);
            } catch (SlashCommandDispatcher.CommandUsageException failure) {
                error(failure.getMessage());
            } catch (ModeResolutionException failure) {
                error(failure.getMessage());
            } catch (IllegalArgumentException failure) {
                error("Invalid command argument.");
            } catch (IOException failure) {
                error("Session storage is unavailable.");
            } catch (CancellationException failure) {
                terminal.println("Turn cancelled.");
            } catch (Exception failure) {
                error("Shell operation failed; use /status and retry.");
            }
            terminal.flush();
            if (keepalive != null) keepalive.activity();
        }
        return ExitCodes.OK;
    }

    private void runTurn(String input) throws Exception {
        ensureSession();
        AgentMessage.Text user = new AgentMessage.Text(AgentMessage.Role.USER, input);
        session = sessions.append(session.id(), user);
        history.add(user);
        CancellationSource cancellation = new CancellationSource();
        activeCancellation = cancellation;
        if (keepalive != null) keepalive.busy(true);
        TerminalRenderer renderer = renderer();
        RenderingEvents events = new RenderingEvents(renderer);
        int requestSize = history.size();
        try {
            AgentResult result = turns.run(UUID.randomUUID().toString(), List.copyOf(history),
                    cancellation, events, prompter);
            events.finish(result.status() == AgentResult.Status.CANCELLED);
            List<AgentMessage> transcript = result.transcript();
            for (int index = requestSize; index < transcript.size(); index++) {
                AgentMessage message = transcript.get(index);
                session = sessions.append(session.id(), message);
                history.add(message);
            }
            if (result.status() != AgentResult.Status.COMPLETED) presentFailure(result);
        } finally {
            renderer.finish();
            terminalReader.awaitIdle();
            activeCancellation = null;
            if (keepalive != null) keepalive.busy(false);
        }
    }

    /** Cancels active work; a consecutive second interrupt exits within two seconds. */
    public void interrupt() {
        CancellationSource cancellation = activeCancellation;
        if (cancellation != null) {
            long now = nanoTime.getAsLong();
            boolean second = consecutiveInterrupt(now);
            lastInterruptNanos.set(now);
            if (second) exit.set(true);
            cancellation.cancel();
            if (turns != null) turns.cancelActive();
            terminalReader.abortAndAwait();
            return;
        }
        terminalReader.abortAndAwait();
    }

    @Override public void help() { terminal.println(HELP); }

    @Override public void newSession() throws Exception {
        ensureEnvironment();
        startNewSession();
        prompter.resetSession();
        terminal.println("New session: " + session.id());
    }

    @Override public void status() throws Exception {
        ensureSession();
        terminal.println(safe("mode=" + environment.mode()
                + " provider=" + environment.provider()
                + " model=" + environment.model()
                + " mcpEndpoint=" + environment.mcpEndpoint()
                + " providerEndpoint=" + environment.providerEndpoint()
                + " session=" + session.id()
                + " turns=" + session.turns()));
    }

    @Override public void tools() throws Exception {
        ensureEnvironment();
        CancellationSource cancellation = new CancellationSource();
        List<ToolDefinition> definitions = turns.refreshTools(cancellation);
        terminal.println(definitions.size() + " tool(s):");
        definitions.stream().map(ToolDefinition::name).sorted()
                .forEach(name -> terminal.println("  " + safe(name)));
    }

    @Override public void model() throws Exception {
        ensureEnvironment();
        terminal.println(safe(environment.provider() + "/" + environment.model()
                + " (read-only; restart shell to change)"));
    }

    @Override public void sessions() throws Exception {
        List<SessionMetadata> listed = sessions.list();
        if (listed.isEmpty()) {
            terminal.println("No saved sessions.");
            return;
        }
        for (SessionMetadata item : listed) {
            String title = item.title().isBlank() ? "(untitled)" : item.title();
            terminal.println(safe(item.id() + "  " + item.turns() + " turn(s)  "
                    + SESSION_TIME.format(item.updatedAt()) + "  " + title));
        }
    }

    @Override public void resume(String id) throws Exception {
        ensureEnvironment();
        SessionContext context = context();
        ResumedSession resumed = sessions.resume(id, context);
        history.clear();
        history.addAll(resumed.messages());
        session = resumed.metadata();
        prompter.resetSession();
        terminal.println("Resumed session: " + session.id() + " (" + session.turns() + " turn(s))");
        if (resumed.mismatch().present()) {
            terminal.println("Warning: session fingerprint mismatch: "
                    + resumed.mismatch().fields().stream()
                            .map(field -> field.name().toLowerCase(Locale.ROOT))
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    @Override public void exit() { exit.set(true); }

    @Override public void error(String message) { terminal.println(safe("error: " + message)); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        exit.set(true);
        CancellationSource cancellation = activeCancellation;
        if (cancellation != null) cancellation.cancel();
        if (keepalive != null) keepalive.close();
        terminalReader.abortAndAwait();
        if (turns != null) turns.close();
        else if (environment != null) environment.close();
    }

    private void ensureSession() throws Exception {
        ensureEnvironment();
        if (session == null) startNewSession();
    }

    private void startNewSession() throws IOException {
        String prompt = systemPrompt.read();
        SessionMetadata created = sessions.create(context());
        List<AgentMessage> initial = new ArrayList<>();
        if (!prompt.isBlank()) {
            AgentMessage.Text system = new AgentMessage.Text(AgentMessage.Role.SYSTEM, prompt);
            created = sessions.append(created.id(), system);
            initial.add(system);
        }
        history.clear();
        history.addAll(initial);
        session = created;
    }

    private SessionContext context() {
        return SessionContext.fromEndpoints(environment.mode(), environment.provider(),
                environment.model(), environment.mcpEndpoint(), environment.instanceId(),
                environment.providerEndpoint());
    }

    private void ensureEnvironment() throws Exception {
        if (environment != null) return;
        environment = environments.resolve(options);
        turns = new TurnRunner(environment, options.maxSteps(), Duration.ofSeconds(options.turnTimeoutSeconds()));
        keepalive = keepaliveFactory.create(turns::keepalive);
        keepalive.start();
    }

    private TerminalRenderer renderer() {
        return new TerminalRenderer(new TerminalAppendable(terminal), RenderConfig.forCapabilities(
                terminal.ansiCapable(), noColor, terminal.dumb(), redactor));
    }

    private boolean handlePromptInterrupt(String partialLine) {
        long now = nanoTime.getAsLong();
        boolean second = consecutiveInterrupt(now);
        lastInterruptNanos.set(now);
        if (second || partialLine == null || partialLine.isEmpty()) {
            exit.set(true);
            return true;
        }
        terminal.println("^C");
        terminal.flush();
        return false;
    }

    private boolean consecutiveInterrupt(long now) {
        long previous = lastInterruptNanos.get();
        return previous != 0 && now >= previous
                && now - previous <= SECOND_INTERRUPT_WINDOW_NANOS;
    }

    private void presentFailure(AgentResult result) {
        AgentError error = result.error().orElse(null);
        String reason = error == null ? result.status().name() : error.code().name();
        terminal.println("Turn ended: " + reason.toLowerCase(Locale.ROOT));
    }

    private String safe(String value) {
        return java.util.Objects.requireNonNull(redactor.apply(value), "redactor result");
    }

    @FunctionalInterface public interface EnvironmentProvider {
        ShellEnvironment resolve(ShellOptions options) throws Exception;
    }
    @FunctionalInterface public interface KeepaliveFactory {
        IdleKeepalive create(IdleKeepalive.Ping ping);
    }
    @FunctionalInterface public interface SystemPromptProvider {
        String read() throws IOException;
    }

    private static final class TerminalAppendable implements Appendable {
        private final ShellTerminal terminal;
        private final StringBuilder line = new StringBuilder();
        TerminalAppendable(ShellTerminal terminal) { this.terminal = terminal; }
        @Override public Appendable append(CharSequence value) {
            return append(value, 0, value.length());
        }
        @Override public Appendable append(CharSequence value, int start, int end) {
            for (int index = start; index < end; index++) append(value.charAt(index));
            return this;
        }
        @Override public Appendable append(char value) {
            if (value == '\n') {
                terminal.println(line.toString());
                terminal.flush();
                line.setLength(0);
            } else if (value != '\r') line.append(value);
            return this;
        }
    }

    private static final class RenderingEvents implements AgentEventListener {
        private final TerminalRenderer renderer;
        private StreamingTextSink text;
        private boolean streamed;
        RenderingEvents(TerminalRenderer renderer) { this.renderer = renderer; }
        @Override public synchronized void onStepStarted(String operationId, int step) {
            finish(false);
            streamed = false;
        }
        @Override public synchronized void onAssistantTextDelta(
                String operationId, int step, String delta) {
            if (text == null) text = renderer.openText();
            streamed = true;
            text.append(delta);
        }
        @Override public synchronized void onAssistantMessage(
                String operationId, int step, AgentMessage.Assistant message) {
            if (text != null) finish(false);
            else if (!streamed && message.text().isPresent()) {
                text = renderer.openText();
                text.append(message.text().get());
                finish(false);
            }
        }
        @Override public synchronized void onToolCallStarted(
                String operationId, int step, ToolCall call) {
            finish(false);
            renderer.presentToolCall(new ToolCallPresentation(
                    call.id(), call.name(), call.argumentsJson()));
        }
        @Override public synchronized void onToolCallResult(String operationId, int step,
                ToolCall call, ToolExecutionResult result) {
            renderer.presentToolResult(new ToolResultPresentation(call.id(), call.name(),
                    !result.error(), result.toJson().toString()));
        }
        synchronized void finish(boolean cancelled) {
            if (text == null) return;
            if (cancelled) text.cancel(); else text.end();
            text = null;
        }
    }
}
