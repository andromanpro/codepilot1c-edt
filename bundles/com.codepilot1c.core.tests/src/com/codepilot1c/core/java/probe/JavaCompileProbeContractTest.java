package com.codepilot1c.core.java.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.junit.Assume;

import com.codepilot1c.core.edt.observability.CommandResult;
import com.codepilot1c.core.edt.observability.CommandRunner;
import com.google.gson.JsonObject;

public class JavaCompileProbeContractTest {

    private static final Set<String> PAYLOAD_KEYS = Set.of(
            "probe_ok", "compiles", "error_code", "snippet_kind", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "diagnostics", "error_count", "warning_count", "truncated", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "duration_ms", "exit_code", "jdk_source", "probe_mode"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    @Test
    public void classpathContainsNoProjectOrEdtEntries() {
        List<String> command = command(Path.of("/jdk/bin/javac"), Path.of("/tmp/a/Probe.java"), //$NON-NLS-1$ //$NON-NLS-2$
                Path.of("/tmp/a/out")); //$NON-NLS-1$

        assertEmptyOption(command, "-classpath"); //$NON-NLS-1$
        assertEmptyOption(command, "-sourcepath"); //$NON-NLS-1$
        assertEmptyOption(command, "-processorpath"); //$NON-NLS-1$
        String joined = String.join(" ", command); //$NON-NLS-1$
        assertFalse(joined.contains("com.codepilot1c")); //$NON-NLS-1$
        assertFalse(joined.contains("com._1c")); //$NON-NLS-1$
        assertFalse(joined.contains("/lib/")); //$NON-NLS-1$
    }

    @Test
    public void argvIsSingleVariableConstruction() {
        String hostileOne = "1\n-Xplugin:evil -J-Dx='quoted value'"; //$NON-NLS-1$
        String hostileTwo = "\" -processor malicious Processor"; //$NON-NLS-1$
        List<String> first = command(Path.of("/jdk/bin/javac"), //$NON-NLS-1$
                Path.of("/tmp/one/Probe.java"), Path.of("/tmp/one/out")); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> second = command(Path.of("/jdk/bin/javac"), //$NON-NLS-1$
                Path.of("/tmp/two/Probe.java"), Path.of("/tmp/two/out")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            if (i == first.size() - 1 || i == first.size() - 2) {
                assertNotEquals(first.get(i), second.get(i));
            } else {
                assertEquals(first.get(i), second.get(i));
            }
        }
        String joined = String.join(" ", first) + String.join(" ", second); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(joined.contains(hostileOne));
        assertFalse(joined.contains(hostileTwo));
    }

    @Test
    public void argvDisablesAnnotationProcessingAndPlugins() {
        List<String> command = command(Path.of("/jdk/bin/javac"), Path.of("/tmp/a/Probe.java"), //$NON-NLS-1$ //$NON-NLS-2$
                Path.of("/tmp/a/out")); //$NON-NLS-1$

        assertTrue(command.contains("-proc:none")); //$NON-NLS-1$
        assertFalse(command.contains("-processor")); //$NON-NLS-1$
        assertFalse(command.stream().anyMatch(value -> value.startsWith("-Xplugin"))); //$NON-NLS-1$
        Set<String> fixedJOptions = Set.of(
                "-J-Xmx256m", "-J-Duser.language=en", "-J-Duser.country=US", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "-J-Dfile.encoding=UTF-8", "-J-Dstdout.encoding=UTF-8", //$NON-NLS-1$ //$NON-NLS-2$
                "-J-Dstderr.encoding=UTF-8"); //$NON-NLS-1$
        assertTrue(command.stream().filter(value -> value.startsWith("-J")).allMatch(fixedJOptions::contains)); //$NON-NLS-1$
    }

    @Test
    public void argvPinsReleaseAndEnglishLocale() {
        List<String> command = command(Path.of("/jdk/bin/javac"), Path.of("/tmp/a/Probe.java"), //$NON-NLS-1$ //$NON-NLS-2$
                Path.of("/tmp/a/out")); //$NON-NLS-1$
        assertEquals("17", command.get(command.indexOf("--release") + 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(command.contains("-J-Duser.language=en")); //$NON-NLS-1$
        assertTrue(command.contains("-J-Duser.country=US")); //$NON-NLS-1$
    }

    @Test
    public void missingJdkProducesDeterministicError() {
        RecordingRunner commands = new RecordingRunner(new CommandResult(0, "", "", false)); //$NON-NLS-1$ //$NON-NLS-2$
        JavaCompileProbeRunner runner = new JavaCompileProbeRunner(commands,
                new JdkLocator(() -> null, () -> null, () -> null));

        ProbeOutcome outcome = runner.run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$

        assertFalse(outcome.probeOk());
        assertEquals("javac_not_available", outcome.errorCode()); //$NON-NLS-1$
        assertEquals("none", outcome.jdkSource()); //$NON-NLS-1$
        assertEquals(0, commands.commands.size());
        assertPayloadKeys(outcome);
    }

    @Test
    public void tooOldJdkProducesDeterministicError() {
        Path home = runtimeJdkHome();
        JavaCompileProbeRunner runner = new JavaCompileProbeRunner(
                (command, timeout) -> new CommandResult(0, "", "", false), //$NON-NLS-1$ //$NON-NLS-2$
                new JdkLocator(() -> home.toString(), () -> null, () -> null,
                        path -> JdkLocator.VersionStatus.TOO_OLD));

        ProbeOutcome outcome = runner.run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$

        assertFalse(outcome.probeOk());
        assertEquals("javac_too_old", outcome.errorCode()); //$NON-NLS-1$
        assertEquals("preference", outcome.jdkSource()); //$NON-NLS-1$
    }

    @Test
    public void timeoutAndOutputCapsAreEnforced() {
        String oversized = "x".repeat(JavaCompileProbeRunner.MAX_DIAGNOSTICS_CHARS + 10); //$NON-NLS-1$
        ProbeOutcome timeout = runnerWith(new CommandResult(-1, "", oversized, true)) //$NON-NLS-1$
                .run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$
        ProbeOutcome capped = runnerWith(new CommandResult(1, "", oversized, false)) //$NON-NLS-1$
                .run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$

        assertEquals("timeout", timeout.errorCode()); //$NON-NLS-1$
        assertFalse(timeout.probeOk());
        assertTrue(timeout.truncated());
        assertTrue(capped.probeOk());
        assertFalse(capped.compiles());
        assertTrue(capped.truncated());
        assertEquals(JavaCompileProbeRunner.MAX_DIAGNOSTICS_CHARS, capped.diagnostics().length());
    }

    @Test
    public void compilationRunsOutOfProcess() {
        RecordingRunner commands = new RecordingRunner(new CommandResult(0, "compiled", "", false)); //$NON-NLS-1$ //$NON-NLS-2$
        ProbeOutcome outcome = runnerWith(commands).run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$

        assertTrue(outcome.probeOk());
        assertTrue(outcome.compiles());
        assertEquals(1, commands.commands.size());
        assertEquals(Duration.ofSeconds(10), commands.timeouts.get(0));
        assertEquals("compiled", outcome.diagnostics()); //$NON-NLS-1$
    }

    @Test
    public void temporaryDirectoryIsRemovedAfterSuccessAndFailure() {
        RecordingRunner success = new RecordingRunner(new CommandResult(0, "", "", false)); //$NON-NLS-1$ //$NON-NLS-2$
        runnerWith(success).run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$
        Path successDir = sourcePath(success).getParent();

        RecordingRunner failure = new RecordingRunner(new CommandResult(1, "", "bad", false)); //$NON-NLS-1$ //$NON-NLS-2$
        runnerWith(failure).run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$
        Path failureDir = sourcePath(failure).getParent();

        assertFalse(Files.exists(successDir));
        assertFalse(Files.exists(failureDir));
    }

    @Test
    public void temporaryDirectoryIsOutsideWorkspaceAndProject() {
        RecordingRunner commands = new RecordingRunner(new CommandResult(0, "", "", false)); //$NON-NLS-1$ //$NON-NLS-2$
        Path project = Path.of("").toAbsolutePath().normalize(); //$NON-NLS-1$
        runnerWith(commands).run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$

        assertFalse(sourcePath(commands).startsWith(project));
    }

    @Test
    public void diagnosticsRedactAbsoluteTempPathAndMapLines() {
        RecordingRunner commands = new RecordingRunner(null);
        commands.resultFactory = command -> {
            Path source = Path.of(command.get(command.size() - 1));
            return new CommandResult(1, "", source + ":2: error: broken", false); //$NON-NLS-1$ //$NON-NLS-2$
        };

        ProbeOutcome outcome = runnerWith(commands).run(true, "broken", SnippetKind.EXPRESSION); //$NON-NLS-1$

        assertEquals("snippet:1: error: broken", outcome.diagnostics()); //$NON-NLS-1$
        assertFalse(outcome.diagnostics().contains("cp1c-javaprobe")); //$NON-NLS-1$
        assertEquals(1, outcome.errorCount());
    }

    @Test
    public void autoKindTriesWrappersInFixedOrderAndReportsFirstFailure() {
        RecordingRunner commands = new RecordingRunner(null);
        commands.resultFactory = command -> {
            int attempt = commands.commands.size();
            Path source = Path.of(command.get(command.size() - 1));
            return new CommandResult(1, "", source + ":2: error: attempt-" + attempt, false); //$NON-NLS-1$ //$NON-NLS-2$
        };

        ProbeOutcome outcome = runnerWith(commands).run(true, "not valid", SnippetKind.AUTO); //$NON-NLS-1$

        assertEquals(4, commands.commands.size());
        assertEquals("UNRESOLVED", outcome.snippetKind()); //$NON-NLS-1$
        assertTrue(outcome.diagnostics().contains("attempt-1")); //$NON-NLS-1$
        assertFalse(outcome.diagnostics().contains("attempt-4")); //$NON-NLS-1$
    }

    @Test
    public void payloadKeySetIsDeterministic() {
        List<ProbeOutcome> outcomes = List.of(
                runnerWith(new CommandResult(0, "", "", false)) //$NON-NLS-1$ //$NON-NLS-2$
                        .run(true, "1 + 1", SnippetKind.EXPRESSION), //$NON-NLS-1$
                runnerWith(new CommandResult(1, "", "snippet:1: error: x", false)) //$NON-NLS-1$ //$NON-NLS-2$
                        .run(true, "x", SnippetKind.EXPRESSION), //$NON-NLS-1$
                ProbeOutcome.failure("javac_not_available", "missing", "none"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                runnerWith(new CommandResult(-1, "", "timeout", true)) //$NON-NLS-1$ //$NON-NLS-2$
                        .run(true, "x", SnippetKind.EXPRESSION), //$NON-NLS-1$
                runnerWith(new CommandResult(0, "", "", false)) //$NON-NLS-1$ //$NON-NLS-2$
                        .run(false, "x", SnippetKind.EXPRESSION)); //$NON-NLS-1$

        outcomes.forEach(JavaCompileProbeContractTest::assertPayloadKeys);
    }

    @Test
    public void isolatedProcessRunnerClearsEnvironmentClosesInputAndLeavesProject() {
        Assume.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Assume.assumeTrue(Files.isExecutable(Path.of("/usr/bin/env"))); //$NON-NLS-1$
        Assume.assumeTrue(Files.isExecutable(Path.of("/usr/bin/wc"))); //$NON-NLS-1$
        Assume.assumeTrue(Files.isExecutable(Path.of("/bin/pwd"))); //$NON-NLS-1$
        CommandRunner isolated = CommandRunner.isolatedProcessBuilder();

        CommandResult environment = isolated.run(List.of("/usr/bin/env"), Duration.ofSeconds(2)); //$NON-NLS-1$
        CommandResult stdin = isolated.run(List.of("/usr/bin/wc", "-c"), Duration.ofSeconds(2)); //$NON-NLS-1$ //$NON-NLS-2$
        CommandResult directory = isolated.run(List.of("/bin/pwd"), Duration.ofSeconds(2)); //$NON-NLS-1$

        assertEquals(0, environment.exitCode());
        assertTrue(environment.stdout().isBlank());
        assertEquals(0, stdin.exitCode());
        assertEquals("0", stdin.stdout().trim()); //$NON-NLS-1$
        assertEquals(0, directory.exitCode());
        assertFalse(Path.of(directory.stdout().trim()).startsWith(Path.of("").toAbsolutePath().normalize())); //$NON-NLS-1$
    }

    private static List<String> command(Path javac, Path source, Path out) {
        return new JavacCommandBuilder(javac).build(source, out);
    }

    private static void assertEmptyOption(List<String> command, String option) {
        int index = command.indexOf(option);
        assertTrue(option, index >= 0);
        assertEquals("", command.get(index + 1)); //$NON-NLS-1$
    }

    private static JavaCompileProbeRunner runnerWith(CommandResult result) {
        return runnerWith(new RecordingRunner(result));
    }

    private static JavaCompileProbeRunner runnerWith(CommandRunner commandRunner) {
        Path home = runtimeJdkHome();
        return new JavaCompileProbeRunner(commandRunner,
                new JdkLocator(() -> home.toString(), () -> null, () -> null));
    }

    private static Path runtimeJdkHome() {
        return Path.of(System.getProperty("java.home")); //$NON-NLS-1$
    }

    private static Path sourcePath(RecordingRunner runner) {
        List<String> command = runner.commands.get(0);
        return Path.of(command.get(command.size() - 1));
    }

    private static void assertPayloadKeys(ProbeOutcome outcome) {
        JsonObject json = ProbePayload.toJson(outcome);
        assertEquals(12, json.size());
        assertEquals(PAYLOAD_KEYS, json.keySet());
        assertEquals("compile_only", json.get("probe_mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class RecordingRunner implements CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private CommandResult result;
        private java.util.function.Function<List<String>, CommandResult> resultFactory;

        private RecordingRunner(CommandResult result) {
            this.result = result;
        }

        @Override
        public CommandResult run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            timeouts.add(timeout);
            return resultFactory == null ? result : resultFactory.apply(command);
        }
    }
}
