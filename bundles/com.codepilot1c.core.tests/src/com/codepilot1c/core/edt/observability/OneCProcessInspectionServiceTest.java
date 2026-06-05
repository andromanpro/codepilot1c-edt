package com.codepilot1c.core.edt.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.Test;

public class OneCProcessInspectionServiceTest {

    @Test
    public void classifiesKnown1CProcesses() {
        OneCProcessSnapshot ibsrv = OneCProcessInspectionService.classify(
                86151L, 1L, "alex", //$NON-NLS-1$
                "/opt/1cv8/8.3.27.2170/ibsrv /Volumes/T9/info_base_1c/do27"); //$NON-NLS-1$
        assertEquals("ibsrv", ibsrv.processType()); //$NON-NLS-1$
        assertTrue(ibsrv.commandLine().contains("1cv8")); //$NON-NLS-1$
        assertTrue(ibsrv.infobasePaths().contains("/Volumes/T9/info_base_1c/do27")); //$NON-NLS-1$

        OneCProcessSnapshot designer = OneCProcessInspectionService.classify(
                86152L, 86151L, "alex", //$NON-NLS-1$
                "/opt/1cv8/8.3.27.2170/1cv8 DESIGNER /F/Volumes/T9/info_base_1c/do27"); //$NON-NLS-1$
        assertEquals("designer_session", designer.processType()); //$NON-NLS-1$
        assertTrue(designer.infobasePaths().contains("/Volumes/T9/info_base_1c/do27")); //$NON-NLS-1$
    }

    @Test
    public void pathSegmentNamedDesignerDoesNotMakeRuntimeSessionDesigner() {
        OneCProcessSnapshot session = OneCProcessInspectionService.classify(
                86155L, 1L, "alex", //$NON-NLS-1$
                "/opt/1cv8/8.3.27.2170/1cv8 ENTERPRISE /F/tmp/designer/base"); //$NON-NLS-1$

        assertEquals("session", session.processType()); //$NON-NLS-1$
        assertTrue(session.infobasePaths().contains("/tmp/designer/base")); //$NON-NLS-1$
    }

    @Test
    public void enrichesProcessRowsWithPortsAndChildren() {
        FakeRunner runner = new FakeRunner();
        runner.add("ps -axo pid,ppid,user,command", //$NON-NLS-1$
                """
                PID PPID USER COMMAND
                86151 1 alex /opt/1cv8/8.3.27.2170/ibsrv /Volumes/T9/info_base_1c/do27
                86152 86151 alex /opt/1cv8/8.3.27.2170/1cv8 DESIGNER /F/Volumes/T9/info_base_1c/do27
                """);
        runner.add("lsof -nP -iTCP -sTCP:LISTEN", //$NON-NLS-1$
                """
                COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
                ibsrv   86151 alex   12u  IPv4 0x01      0t0  TCP *:1541 (LISTEN)
                """);

        OneCProcessInspectionService service = new OneCProcessInspectionService(new EmptyGateway(), runner);

        List<OneCProcessSnapshot> snapshots = service.inspect();

        OneCProcessSnapshot ibsrv = find(snapshots, 86151L);
        assertEquals(List.of(Integer.valueOf(1541)), ibsrv.ports());
        assertEquals(List.of(Long.valueOf(86152L)), ibsrv.children());
        assertEquals("designer_session", find(snapshots, 86152L).processType()); //$NON-NLS-1$
    }

    private static OneCProcessSnapshot find(List<OneCProcessSnapshot> snapshots, long pid) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.pid() == pid)
                .findFirst()
                .orElseThrow();
    }

    private static class EmptyGateway extends EdtObservabilityGateway {
        @Override
        public List<ProcessHandle> allProcesses() {
            return List.of();
        }
    }

    private static class FakeRunner implements CommandRunner {
        private final java.util.Map<String, String> stdoutByCommand = new java.util.HashMap<>();

        void add(String command, String stdout) {
            stdoutByCommand.put(command, stdout);
        }

        @Override
        public CommandResult run(List<String> command, Duration timeout) {
            return new CommandResult(0, stdoutByCommand.getOrDefault(String.join(" ", command), ""), "", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
}
