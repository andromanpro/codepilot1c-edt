package com.codepilot1c.core.edt.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InfobaseLockServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void directoryInputInspectsDataAndLockFiles() throws Exception {
        Path infobase = temporaryFolder.newFolder("do27").toPath(); //$NON-NLS-1$
        Files.createFile(infobase.resolve("1Cv8.1CD")); //$NON-NLS-1$
        Files.createFile(infobase.resolve("1Cv8.1CL")); //$NON-NLS-1$
        RecordingRunner runner = new RecordingRunner();

        InfobaseLockSnapshot snapshot = new InfobaseLockService(new EmptyGateway(), runner)
                .inspect(infobase.toString());

        assertTrue(runner.commands().contains(List.of("lsof", "-nP", //$NON-NLS-1$ //$NON-NLS-2$
                infobase.resolve("1Cv8.1CD").toString()))); //$NON-NLS-1$
        assertTrue(runner.commands().contains(List.of("lsof", "-nP", //$NON-NLS-1$ //$NON-NLS-2$
                infobase.resolve("1Cv8.1CL").toString()))); //$NON-NLS-1$
        assertEquals("unknown", snapshot.lockKind()); //$NON-NLS-1$
        assertTrue(snapshot.evidence().stream().anyMatch(line -> line.contains("exit=1"))); //$NON-NLS-1$
    }

    @Test
    public void connectionStringExtractsFileBasePath() {
        RecordingRunner runner = new RecordingRunner();
        runner.addStdout("ps -axo pid,ppid,user,command", //$NON-NLS-1$
                "86152 1 alex /opt/1cv8/8.3.27.2170/1cv8 DESIGNER /F/Volumes/T9/info_base_1c/do27"); //$NON-NLS-1$
        runner.addStdout("lsof -nP /Volumes/T9/info_base_1c/do27/1Cv8.1CD", //$NON-NLS-1$
                """
                COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
                1cv8    86152 alex   14u   REG   1,4        0  42 /Volumes/T9/info_base_1c/do27/1Cv8.1CD
                """);

        InfobaseLockSnapshot snapshot = new InfobaseLockService(new EmptyGateway(), runner)
                .inspect("Srvr=\"localhost\";Ref=\"Demo\";/F/Volumes/T9/info_base_1c/do27"); //$NON-NLS-1$

        assertEquals("/Volumes/T9/info_base_1c/do27", snapshot.normalizedPath()); //$NON-NLS-1$
        assertEquals("configuration", snapshot.lockKind()); //$NON-NLS-1$
        assertTrue(snapshot.confidence() > 0.8d);
        assertTrue(snapshot.evidence().stream().anyMatch(line -> line.contains("DESIGNER"))); //$NON-NLS-1$
    }

    @Test
    public void plainAbsolutePathStartingWithFIsPreserved() {
        RecordingRunner runner = new RecordingRunner();

        InfobaseLockSnapshot snapshot = new InfobaseLockService(new EmptyGateway(), runner)
                .inspect("/Foo/base"); //$NON-NLS-1$

        assertEquals("/Foo/base", snapshot.normalizedPath()); //$NON-NLS-1$
        assertTrue(runner.commands().contains(List.of("lsof", "-nP", "/Foo/base/1Cv8.1CD"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void regularRuntimeProcessIsSessionLock() {
        RecordingRunner runner = new RecordingRunner();
        runner.addStdout("ps -axo pid,ppid,user,command", //$NON-NLS-1$
                "86153 1 alex /opt/1cv8/8.3.27.2170/1cv8 ENTERPRISE /F/tmp/base"); //$NON-NLS-1$
        runner.addStdout("lsof -nP /tmp/base/1Cv8.1CD", //$NON-NLS-1$
                """
                COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
                1cv8    86153 alex   14u   REG   1,4        0  42 /tmp/base/1Cv8.1CD
                """);

        InfobaseLockSnapshot snapshot = new InfobaseLockService(new EmptyGateway(), runner)
                .inspect("/tmp/base"); //$NON-NLS-1$

        assertEquals("session", snapshot.lockKind()); //$NON-NLS-1$
        assertTrue(snapshot.confidence() > 0.5d);
    }

    @Test
    public void runtimeProcessWithDesignerPathSegmentIsSessionLock() {
        RecordingRunner runner = new RecordingRunner();
        runner.addStdout("ps -axo pid,ppid,user,command", //$NON-NLS-1$
                "86155 1 alex /opt/1cv8/8.3.27.2170/1cv8 ENTERPRISE /F/tmp/designer/base"); //$NON-NLS-1$
        runner.addStdout("lsof -nP /tmp/designer/base/1Cv8.1CD", //$NON-NLS-1$
                """
                COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
                1cv8    86155 alex   14u   REG   1,4        0  42 /tmp/designer/base/1Cv8.1CD
                """);

        InfobaseLockSnapshot snapshot = new InfobaseLockService(new EmptyGateway(), runner)
                .inspect("/tmp/designer/base"); //$NON-NLS-1$

        assertEquals("session", snapshot.lockKind()); //$NON-NLS-1$
        assertTrue(snapshot.confidence() > 0.5d);
    }

    @Test
    public void ibcmdConfigWithoutImportIsSessionLock() {
        RecordingRunner runner = new RecordingRunner();
        runner.addStdout("ps -axo pid,ppid,user,command", //$NON-NLS-1$
                "86154 1 alex /opt/1cv8/8.3.27.2170/ibcmd config export --database /tmp/base"); //$NON-NLS-1$
        runner.addStdout("lsof -nP /tmp/base/1Cv8.1CD", //$NON-NLS-1$
                """
                COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
                ibcmd   86154 alex   14u   REG   1,4        0  42 /tmp/base/1Cv8.1CD
                """);

        InfobaseLockSnapshot snapshot = new InfobaseLockService(new EmptyGateway(), runner)
                .inspect("/tmp/base"); //$NON-NLS-1$

        assertEquals("session", snapshot.lockKind()); //$NON-NLS-1$
        assertTrue(snapshot.confidence() > 0.5d);
    }

    @Test
    public void malformedPathReturnsUnknownWithEvidence() {
        RecordingRunner runner = new RecordingRunner();

        InfobaseLockSnapshot snapshot = new InfobaseLockService(new EmptyGateway(), runner)
                .inspect("bad\0path"); //$NON-NLS-1$

        assertEquals("unknown", snapshot.lockKind()); //$NON-NLS-1$
        assertEquals("", snapshot.normalizedPath()); //$NON-NLS-1$
        assertTrue(snapshot.evidence().stream()
                .anyMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("invalid path"))); //$NON-NLS-1$
        assertTrue(runner.commands().isEmpty());
    }

    private static class EmptyGateway extends EdtObservabilityGateway {
        @Override
        public List<ProcessHandle> allProcesses() {
            return List.of();
        }
    }

    private static class RecordingRunner implements CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final java.util.Map<String, String> stdoutByCommand = new java.util.HashMap<>();

        List<List<String>> commands() {
            return commands;
        }

        void addStdout(String command, String stdout) {
            stdoutByCommand.put(command, stdout);
        }

        @Override
        public CommandResult run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            String stdout = stdoutByCommand.get(String.join(" ", command)); //$NON-NLS-1$
            if (stdout == null) {
                return new CommandResult(1, "", "not open", false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new CommandResult(0, stdout, "", false); //$NON-NLS-1$
        }
    }
}
