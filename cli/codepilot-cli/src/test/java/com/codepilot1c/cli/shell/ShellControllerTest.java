/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.shell.session.SessionContext;
import com.codepilot1c.cli.shell.session.SessionMetadata;
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

public class ShellControllerTest {
    private static final String ENDPOINT = "http://127.0.0.1:8765/mcp";
    private static final String INSTANCE = "11111111-2222-3333-4444-555555555555";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everySlashCommandIsDeterministicAndModelIsReadOnly() throws Exception {
        SessionStore store = store("slashes", UnaryOperator.identity());
        SessionMetadata saved = store.create(context());
        store.append(saved.id(), new AgentMessage.Text(AgentMessage.Role.USER, "saved question"));
        ScriptedTerminal terminal = new ScriptedTerminal("/help", "/status", "/tools", "/model",
                "/sessions", "/new", "/resume " + saved.id(), "/model changed", "/unknown", "/exit");
        ScriptedModel model = new ScriptedModel();
        FakeToolSession tools = new FakeToolSession(readTool());

        try (ShellController controller = controller(terminal, store, model, tools,
                UnaryOperator.identity())) {
            assertEquals(ExitCodes.OK, controller.run());
        }

        String output = terminal.output();
        assertTrue(output.contains("/resume <id>"));
        assertTrue(output.contains("mode=connected provider=EDT model=model-a"));
        assertTrue(output.contains("1 tool(s):"));
        assertTrue(output.contains("read_file"));
        assertTrue(output.contains("EDT/model-a (read-only; restart shell to change)"));
        assertTrue(output.contains(saved.id().toString()));
        assertTrue(output.contains("New session:"));
        assertTrue(output.contains("Resumed session: " + saved.id()));
        assertTrue(output.contains("/model takes no arguments"));
        assertTrue(output.contains("Unknown command: /unknown"));
        assertEquals(0, model.requests.size());
        assertTrue(tools.refreshes.get() >= 1);
    }

    @Test public void carriesCompleteTranscriptAcrossTurnsAndRendersStreamingDeltas() throws Exception {
        ScriptedTerminal terminal = new ScriptedTerminal("first", "second", "/exit");
        ScriptedModel model = new ScriptedModel(
                new Streamed("hello ", "world"), new Streamed("second ", "answer"));
        SessionStore store = store("history", UnaryOperator.identity());
        FakeToolSession tools = new FakeToolSession();

        try (ShellController controller = controller(terminal, store, model,
                tools, UnaryOperator.identity())) {
            assertEquals(ExitCodes.OK, controller.run());
        }

        assertTrue(terminal.output().contains("hello world"));
        assertTrue(terminal.output().contains("second answer"));
        assertEquals(2, model.requests.size());
        // One catalog refresh precedes each independent runtime turn.
        assertEquals(2, tools.refreshes.get());
        List<AgentMessage> second = model.requests.get(1).messages();
        assertEquals(3, second.size());
        assertEquals("first", ((AgentMessage.Text) second.get(0)).content());
        assertEquals("hello world", ((AgentMessage.Assistant) second.get(1)).text().orElseThrow());
        assertEquals("second", ((AgentMessage.Text) second.get(2)).content());
        var resumed = store.resume(store.list().get(0).id());
        assertEquals(4, resumed.messages().size());
        assertEquals(2, resumed.metadata().turns());
    }

    @Test public void approvalSupportsAllowDenyAndAllowForSession() throws Exception {
        ToolDefinition write = new ToolDefinition("write_file", "write", schema(),
                new ToolAnnotations("Write file", false, false, true));
        FakeToolSession tools = new FakeToolSession(write);
        ScriptedModel model = new ScriptedModel(
                new Calls(new ToolCall("c1", "write_file", "{}")), new Streamed("allowed"),
                new Calls(new ToolCall("c2", "write_file", "{}")), new Streamed("still allowed"));
        ScriptedTerminal terminal = new ScriptedTerminal("one", "a", "two", "/exit");
        SessionStore store = store("allow-session", UnaryOperator.identity());

        try (ShellController controller = controller(terminal, store, model, tools,
                UnaryOperator.identity())) {
            controller.run();
        }
        assertEquals(2, tools.executions.get());
        assertEquals(1, occurrences(terminal.output(), "Allow? [y]es/[n]o/[a]ll session:"));

        FakeToolSession deniedTools = new FakeToolSession(write);
        ScriptedModel deniedModel = new ScriptedModel(
                new Calls(new ToolCall("d1", "write_file", "{}")), new Streamed("recovered"));
        ScriptedTerminal deniedTerminal = new ScriptedTerminal("deny", "n", "/exit");
        SessionStore deniedStore = store("deny", UnaryOperator.identity());
        try (ShellController controller = controller(deniedTerminal, deniedStore, deniedModel,
                deniedTools, UnaryOperator.identity())) {
            controller.run();
        }
        assertEquals(0, deniedTools.executions.get());
        AgentMessage.Tool denial = deniedModel.requests.get(1).messages().stream()
                .filter(AgentMessage.Tool.class::isInstance).map(AgentMessage.Tool.class::cast)
                .findFirst().orElseThrow();
        assertEquals("CONFIRMATION_DENIED", denial.result().code());
        assertTrue(deniedTerminal.output().contains("tool-result write_file [d1] error"));

        FakeToolSession onceTools = new FakeToolSession(write);
        ScriptedModel onceModel = new ScriptedModel(
                new Calls(new ToolCall("y1", "write_file", "{}")), new Streamed("done"));
        ScriptedTerminal onceTerminal = new ScriptedTerminal("allow once", "y", "/exit");
        try (ShellController controller = controller(onceTerminal,
                store("allow-once", UnaryOperator.identity()), onceModel, onceTools,
                UnaryOperator.identity())) {
            controller.run();
        }
        assertEquals(1, onceTools.executions.get());
        assertEquals(1, occurrences(onceTerminal.output(), "Allow? [y]es/[n]o/[a]ll session:"));
    }

    @Test public void unknownRiskRequiresConfirmationWhileReadOnlyAnnotationDoesNot() throws Exception {
        assertTrue(DangerousToolFallback.requiresConfirmation(
                new ToolDefinition("legacy", "", schema())));
        assertFalse(DangerousToolFallback.requiresConfirmation(new ToolDefinition(
                "read", "", schema(), new ToolAnnotations("Read", false, true, false))));
        assertTrue(DangerousToolFallback.requiresConfirmation(new ToolDefinition(
                "mutate", "", schema(), new ToolAnnotations("Mutate", false, false, false))));
    }

    @Test public void resumeReportsOnlyFingerprintFieldNamesAndStatusRedactsSecrets() throws Exception {
        ShellSecretRedactor redactor = new ShellSecretRedactor();
        char[] secret = "top-secret".toCharArray();
        redactor.add(secret);
        SessionStore store = store("redacted", redactor);
        SessionMetadata saved = store.create(SessionContext.fromEndpoint(
                "standalone", "other", "old", "http://localhost:9999/mcp", INSTANCE));
        ScriptedTerminal terminal = new ScriptedTerminal("/resume " + saved.id(), "/status", "/exit");
        ShellEnvironment environment = environment(new ScriptedModel(), new FakeToolSession(),
                "provider-top-secret", "model-top-secret");
        try (redactor; ShellController controller = controller(
                terminal, store, environment, redactor)) {
            controller.run();
        }
        assertFalse(terminal.output().contains("top-secret"));
        assertTrue(terminal.output().contains("<redacted>"));
        assertTrue(terminal.output().contains("fingerprint mismatch: mode, provider, model, endpoint"));
        assertFalse(terminal.output().contains("localhost:9999"));
    }

    private ShellController controller(ScriptedTerminal terminal, SessionStore store,
            ScriptedModel model, FakeToolSession tools, UnaryOperator<String> redactor) {
        return controller(terminal, store, environment(model, tools, "EDT", "model-a"), redactor);
    }

    private ShellController controller(ScriptedTerminal terminal, SessionStore store,
            ShellEnvironment environment, UnaryOperator<String> redactor) {
        return new ShellController(terminal, options(), ignored -> environment, store, "", redactor,
                ping -> new IdleKeepalive(Duration.ofDays(1), ping, new PassiveScheduler()));
    }

    private ShellEnvironment environment(ScriptedModel model, FakeToolSession tools,
            String provider, String selectedModel) {
        return new ShellEnvironment("connected", provider, selectedModel, ENDPOINT, INSTANCE,
                model, tools, () -> { });
    }

    private SessionStore store(String name, UnaryOperator<String> redactor) throws IOException {
        Path root = temporary.newFolder(name).toPath();
        return new SessionStore(root, redactor, warning -> { });
    }

    private static SessionContext context() {
        return SessionContext.fromEndpoint("connected", "EDT", "model-a", ENDPOINT, INSTANCE);
    }

    private static ShellOptions options() {
        return new ShellOptions(ShellOptions.Mode.CONNECTED, INSTANCE, null, null,
                false, null, null, null, null, false, 8, 30, null);
    }

    private static ToolDefinition readTool() {
        return new ToolDefinition("read_file", "read", schema(),
                new ToolAnnotations("Read file", false, true, false));
    }

    private static JsonObject schema() {
        JsonObject value = new JsonObject();
        value.addProperty("type", "object");
        return value;
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) count++;
        return count;
    }

    private sealed interface ModelStep permits Streamed, Calls { }
    private record Streamed(String... deltas) implements ModelStep { }
    private record Calls(ToolCall call) implements ModelStep { }

    private static final class ScriptedModel implements StreamingAgentModel {
        private final Deque<ModelStep> steps = new ArrayDeque<>();
        private final List<AgentModel.Request> requests = new CopyOnWriteArrayList<>();
        ScriptedModel(ModelStep... steps) { this.steps.addAll(List.of(steps)); }
        @Override public CompletionStage<AgentMessage.Assistant> complete(AgentModel.Request request,
                CancellationToken cancellation, StreamObserver observer) {
            requests.add(request);
            ModelStep step = steps.removeFirst();
            if (step instanceof Streamed streamed) {
                StringBuilder text = new StringBuilder();
                for (String delta : streamed.deltas()) {
                    text.append(delta);
                    observer.onTextDelta(delta);
                }
                return CompletableFuture.completedFuture(AgentMessage.Assistant.text(text.toString()));
            }
            return CompletableFuture.completedFuture(AgentMessage.Assistant.tools(
                    List.of(((Calls) step).call())));
        }
    }

    private static final class FakeToolSession implements ShellToolSession {
        private final List<ToolDefinition> definitions;
        private final AtomicInteger refreshes = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        FakeToolSession(ToolDefinition... definitions) { this.definitions = List.of(definitions); }
        @Override public ToolRuntime runtime() {
            return new ToolRuntime() {
                @Override public List<ToolDefinition> tools() { return definitions; }
                @Override public CompletionStage<ToolExecutionResult> execute(String name,
                        JsonObject arguments, CancellationToken cancellation) {
                    executions.incrementAndGet();
                    return CompletableFuture.completedFuture(ToolExecutionResult.success(new JsonObject()));
                }
            };
        }
        @Override public CompletionStage<List<ToolDefinition>> refresh() {
            refreshes.incrementAndGet();
            return CompletableFuture.completedFuture(definitions);
        }
        @Override public CompletionStage<Void> ping() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<ShellToolSession> reinitialize() {
            return CompletableFuture.completedFuture(this);
        }
        @Override public boolean isExpired(Throwable failure) { return false; }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static final class ScriptedTerminal implements ShellTerminal {
        private final Deque<String> input = new ArrayDeque<>();
        private final StringBuilder output = new StringBuilder();
        ScriptedTerminal(String... lines) { input.addAll(List.of(lines)); }
        @Override public synchronized String readLine(String prompt) {
            output.append(prompt);
            if (input.isEmpty()) return null;
            return input.removeFirst();
        }
        @Override public synchronized void println(String text) { output.append(text).append('\n'); }
        @Override public void flush() { }
        @Override public void close() { }
        synchronized String output() { return output.toString(); }
    }

    private static final class PassiveScheduler implements IdleKeepalive.Scheduler {
        @Override public IdleKeepalive.Cancellable schedule(Runnable task, Duration delay) {
            return () -> { };
        }
        @Override public void close() { }
    }
}
