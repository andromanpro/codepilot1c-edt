/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.HostSystem;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StartRequest;

/**
 * Start-failure observability and ownership contract.
 *
 * <p>The WP-N0 consumer saw {@code readiness_timeout} with an empty stderr and a failure JSON that
 * carried only {@code error} and {@code message}. Nothing in that answer let an operator reach the
 * captured process log or see which instance/workspace/port had failed, so the real cause (the
 * discovered launcher rejecting the Eclipse application arguments) stayed invisible.</p>
 */
public class EdtStartFailureDiagnosticsTest {
    private static final String ID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_ID = "99999999-8888-7777-6666-555555555555";

    @Test public void readinessTimeoutCarriesSafeActionableDiagnostics() {
        Fixture fixture = new Fixture();
        fixture.probe = uri -> new ProbeResult(false, 503, "HTTP 503");

        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofMillis(250))));

        assertEquals("readiness_timeout", failure.error());
        assertEquals(ExitCodes.EDT_UNAVAILABLE, failure.exitCode());
        Map<String, Object> details = failure.details();
        assertEquals(ID, details.get("instanceId"));
        assertEquals("/workspace", details.get("workspace"));
        assertEquals(9123, details.get("port"));
        assertEquals(Path.of("/logs/" + ID + ".log").toString(), details.get("logFile"));
        assertEquals("http://127.0.0.1:9123", details.get("readinessUrl"));
        assertEquals("eclipse", details.get("launcherKind"));

        Map<?, ?> lastProbe = (Map<?, ?>) details.get("lastProbe");
        assertEquals(Boolean.FALSE, lastProbe.get("reachable"));
        assertEquals(503, lastProbe.get("httpStatus"));
        assertEquals("HTTP 503", lastProbe.get("detail"));
        assertNoLaunchSecrets(details);
    }

    @Test public void earlyProcessExitCarriesTheSameDiagnosticsSoTheCapturedLogIsReachable() {
        Fixture fixture = new Fixture();
        fixture.process.alive = false;
        fixture.probe = uri -> new ProbeResult(false, 0, "ConnectException");

        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofSeconds(1))));

        assertEquals("process_exited", failure.error());
        Map<String, Object> details = failure.details();
        assertEquals(ID, details.get("instanceId"));
        assertEquals(Path.of("/logs/" + ID + ".log").toString(), details.get("logFile"));
        assertEquals("ConnectException", ((Map<?, ?>) details.get("lastProbe")).get("detail"));
        assertNoLaunchSecrets(details);
    }

    @Test public void interruptedStartCarriesDiagnosticsAndClearsTheInterruptForTheCaller() {
        Fixture fixture = new Fixture();
        fixture.probe = uri -> new ProbeResult(false, 503, "HTTP 503");
        fixture.interruptOnWait = true;

        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofSeconds(30))));
        assertTrue(Thread.interrupted());

        assertEquals("start_interrupted", failure.error());
        assertEquals(ID, failure.details().get("instanceId"));
        assertNoLaunchSecrets(failure.details());
    }

    @Test public void failedStartTerminatesOnlyTheLaunchedProcessAndDeletesOnlyItsOwnRecord() throws Exception {
        Fixture fixture = new Fixture();
        fixture.probe = uri -> new ProbeResult(false, 503, "HTTP 503");
        // A second CLI-owned instance and a same-PID impostor must both survive an unrelated failure.
        fixture.writeRecord(OTHER_ID, 4242, 9124);
        FakeProcess foreign = new FakeProcess(4242, "unrelated-program");
        fixture.processes.put(4242L, foreign);

        assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofMillis(250))));

        assertTrue(fixture.process.destroyCalled);
        assertFalse("cleanup must never touch a process the CLI did not launch", foreign.destroyCalled);
        assertFalse(foreign.forceCalled);
        assertEquals(Optional.empty(), fixture.registry().find(ID));
        assertTrue("unrelated registry records must survive", fixture.registry().find(OTHER_ID).isPresent());
    }

    @Test public void timeoutCleanupSkipsAProcessWhoseIdentityMarkerNoLongerMatchesTheOwnedInstance()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.probe = uri -> new ProbeResult(false, 503, "HTTP 503");
        // The launched PID was recycled by the OS into a foreign program before the timeout fired.
        fixture.rewriteCommandLineOnLaunch = "/usr/bin/some-other-daemon --serve";

        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofMillis(250))));

        assertEquals("readiness_timeout", failure.error());
        assertFalse("a recycled PID must never be killed", fixture.process.destroyCalled);
        assertFalse(fixture.process.forceCalled);
        assertEquals(Optional.empty(), fixture.registry().find(ID));
    }

    @Test public void cleanupStillTerminatesTheOwnedProcessWhenTheOperatingSystemHidesTheCommandLine()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.probe = uri -> new ProbeResult(false, 503, "HTTP 503");
        // An unreadable command line means "cannot tell", not "not ours": the handle came from our
        // own launcher, so refusing to terminate here would leak the process we started.
        fixture.hideCommandLineOnLaunch = true;

        assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofMillis(250))));

        assertTrue(fixture.process.destroyCalled);
        assertEquals(Optional.empty(), fixture.registry().find(ID));
    }

    private static void assertNoLaunchSecrets(Map<String, Object> details) {
        String rendered = String.valueOf(details);
        assertFalse("diagnostics must not leak the launch command line", rendered.contains("-vmargs"));
        assertFalse(rendered.contains("-Dcodepilot.instance.registryDir"));
        assertFalse(rendered.contains("-Dcodepilot.mcp.host.http.port"));
    }

    private static final class Fixture {
        final TestHost host = new TestHost();
        final InstanceRegistryTest.MemoryFiles files = new InstanceRegistryTest.MemoryFiles();
        final MutableClock clock = new MutableClock(Instant.parse("2026-08-25T07:00:00Z"));
        final Map<Long, ProcessHandleFacade> processes = new HashMap<>();
        final FakeProcess process = new FakeProcess(314, "java -Dcodepilot.instance.id=" + ID);
        final List<URI> shutdownRequests = new ArrayList<>();
        boolean interruptOnWait;
        String rewriteCommandLineOnLaunch;
        boolean hideCommandLineOnLaunch;
        com.codepilot1c.cli.EndpointProbe probe = uri -> new ProbeResult(true, 200, "HTTP 200");

        Fixture() {
            host.directories.add("/edt");
            host.files.add("/edt/1cedt");
            host.directories.add("/workspace");
        }

        InstanceRegistry registry() { return new InstanceRegistry(files, Path.of("/registry")); }

        EdtSupervisor supervisor() {
            ProcessLauncher launcher = (command, stdout, stderr) -> {
                process.commandLine = hideCommandLineOnLaunch ? null
                        : rewriteCommandLineOnLaunch != null
                                ? rewriteCommandLineOnLaunch : String.join(" ", command);
                processes.put(process.pid(), process);
                return process;
            };
            WaitStrategy wait = duration -> {
                if (interruptOnWait) throw new InterruptedException("test");
                clock.advance(duration);
            };
            return new EdtSupervisor(new EdtInstallationDiscovery(host), files, registry(), launcher,
                    pid -> Optional.ofNullable(processes.get(pid)), ignored -> true, probe,
                    shutdownRequests::add, clock, wait, () -> UUID.fromString(ID), Path.of("/logs"));
        }

        void writeRecord(String id, long pid, int port) throws Exception {
            registry().write(new InstanceRecord(1, id, pid, port, "http://127.0.0.1:" + port,
                    "/workspace", "/edt", "headless", "cli", clock.instant(), null, null, "/logs/" + id + ".log"));
        }
    }

    private static final class FakeProcess implements ProcessHandleFacade {
        final long pid;
        boolean alive = true;
        boolean destroyCalled;
        boolean forceCalled;
        String commandLine;
        FakeProcess(long pid, String commandLine) {
            this.pid = pid;
            this.commandLine = commandLine;
        }
        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive; }
        @Override public boolean destroy() { destroyCalled = true; alive = false; return true; }
        @Override public boolean destroyForcibly() { forceCalled = true; alive = false; return true; }
        @Override public Optional<String> commandLine() { return Optional.ofNullable(commandLine); }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class TestHost implements HostSystem {
        final List<String> directories = new ArrayList<>();
        final List<String> files = new ArrayList<>();
        @Override public String osName() { return "Linux"; }
        @Override public String javaVersion() { return "17"; }
        @Override public String userHome() { return "/synthetic-home"; }
        @Override public String environment(String name) { return null; }
        @Override public String systemProperty(String name) { return null; }
        @Override public boolean isDirectory(String path) { return directories.contains(path); }
        @Override public boolean isRegularFile(String path) { return files.contains(path); }
        @Override public boolean isReadable(String path) { return files.contains(path); }
        @Override public List<String> children(String directory) { return List.of(); }
    }
}
