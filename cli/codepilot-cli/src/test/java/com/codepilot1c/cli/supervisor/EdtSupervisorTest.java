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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.HostSystem;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StartRequest;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StatusItem;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StopRequest;

public class EdtSupervisorTest {
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test public void startsHeadlessProcessRegistersItAndWaitsForReadinessWithoutSleeping() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger probes = new AtomicInteger();
        fixture.probe = uri -> probes.incrementAndGet() == 1
                ? new ProbeResult(false, 503, "HTTP 503") : new ProbeResult(true, 200, "HTTP 200");

        var result = fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123, Duration.ofSeconds(2)));

        assertEquals("ready", result.state());
        assertEquals(ID, result.instance().instanceId());
        assertEquals(314L, result.instance().pid());
        assertEquals("http://127.0.0.1:9123", result.instance().baseUrl());
        assertEquals("cli", result.instance().owner());
        assertTrue(fixture.launchedCommand.contains("-application"));
        assertTrue(fixture.launchedCommand.contains("com.codepilot1c.core.headless"));
        assertTrue(fixture.launchedCommand.contains("-Dcodepilot.instance.id=" + ID));
        assertEquals(fixture.launchedStdout, fixture.launchedStderr);
        assertEquals(result.instance(), fixture.registry().find(ID).orElseThrow());
        assertEquals(1, fixture.waitCalls);
    }

    @Test public void rejectsLockedWorkspaceAndUnavailablePortBeforeLaunching() {
        Fixture locked = new Fixture();
        locked.files.values.put(Path.of("/workspace/.metadata/.lock"), "locked");
        SupervisorException lockError = assertThrows(SupervisorException.class,
                () -> locked.supervisor().start(new StartRequest("/workspace", "/edt", 9123, Duration.ofSeconds(1))));
        assertEquals("workspace_locked", lockError.error());
        assertEquals(ExitCodes.EDT_UNAVAILABLE, lockError.exitCode());
        assertEquals(List.of(), locked.launchedCommand);

        Fixture port = new Fixture();
        port.portAvailable = false;
        SupervisorException portError = assertThrows(SupervisorException.class,
                () -> port.supervisor().start(new StartRequest("/workspace", "/edt", 9123, Duration.ofSeconds(1))));
        assertEquals("port_unavailable", portError.error());
        assertEquals(List.of(), port.launchedCommand);
    }

    @Test public void timeoutTerminatesOwnedProcessAndRemovesRegistryRecord() throws Exception {
        Fixture fixture = new Fixture();
        fixture.probe = uri -> new ProbeResult(false, 503, "HTTP 503");

        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofMillis(250))));

        assertEquals("readiness_timeout", failure.error());
        assertTrue(fixture.process.destroyCalled);
        assertFalse(fixture.process.alive);
        assertEquals(Optional.empty(), fixture.registry().find(ID));
    }

    @Test public void earlyExitIsReportedAndRegistryIsRemoved() throws Exception {
        Fixture fixture = new Fixture();
        fixture.process.alive = false;
        fixture.probe = uri -> new ProbeResult(false, 0, "ConnectException");

        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new StartRequest("/workspace", "/edt", 9123,
                        Duration.ofSeconds(1))));

        assertEquals("process_exited", failure.error());
        assertEquals(Optional.empty(), fixture.registry().find(ID));
    }

    @Test public void stopUsesGracefulRequestThenDestroyAndCleansStaleRecords() throws Exception {
        Fixture fixture = new Fixture();
        fixture.register("cli", fixture.clock.instant(), "/log");
        fixture.process.commandLine = "java -Dcodepilot.instance.id=" + ID;
        fixture.processes.put(314L, fixture.process);

        var stopped = fixture.supervisor().stop(new StopRequest(ID, false, false, Duration.ofSeconds(1)));

        assertTrue(stopped.complete());
        assertEquals("stopped", stopped.items().get(0).state());
        assertEquals(List.of(URI.create("http://127.0.0.1:9123")), fixture.shutdownRequests);
        assertTrue(fixture.process.destroyCalled);
        assertEquals(Optional.empty(), fixture.registry().find(ID));

        Fixture stale = new Fixture();
        stale.register("cli", stale.clock.instant(), null);
        var cleaned = stale.supervisor().stop(new StopRequest(ID, false, false, Duration.ofSeconds(1)));
        assertEquals("stale_removed", cleaned.items().get(0).state());
        assertEquals(Optional.empty(), stale.registry().find(ID));
    }

    @Test public void forceIsRequiredForForcibleTermination() throws Exception {
        Fixture noForce = new Fixture();
        noForce.process.destroyStops = false;
        noForce.register("cli", noForce.clock.instant(), "/log");
        noForce.processes.put(314L, noForce.process);
        var pending = noForce.supervisor().stop(new StopRequest(ID, false, false, Duration.ofMillis(200)));
        assertFalse(pending.complete());
        assertEquals("still_running", pending.items().get(0).state());
        assertFalse(noForce.process.forceCalled);

        Fixture forced = new Fixture();
        forced.process.destroyStops = false;
        forced.register("cli", forced.clock.instant(), "/log");
        forced.processes.put(314L, forced.process);
        var complete = forced.supervisor().stop(new StopRequest(ID, false, true, Duration.ofMillis(200)));
        assertTrue(complete.complete());
        assertTrue(forced.process.forceCalled);
    }

    @Test public void neverTerminatesExternalOwnerOrPidIdentityMismatch() throws Exception {
        Fixture external = new Fixture();
        external.register("external", external.clock.instant(), "/log");
        external.processes.put(314L, external.process);
        var denied = external.supervisor().stop(new StopRequest(ID, false, true, Duration.ofSeconds(1)));
        assertFalse(denied.complete());
        assertEquals("not_owned", denied.items().get(0).state());
        assertFalse(external.process.destroyCalled);

        Fixture reusedPid = new Fixture();
        reusedPid.register("cli", reusedPid.clock.instant(), "/log");
        reusedPid.process.commandLine = "unrelated-program";
        reusedPid.processes.put(314L, reusedPid.process);
        var mismatch = reusedPid.supervisor().stop(new StopRequest(ID, false, true, Duration.ofSeconds(1)));
        assertEquals("identity_mismatch", mismatch.items().get(0).state());
        assertFalse(reusedPid.process.destroyCalled);
        assertTrue(reusedPid.registry().find(ID).isPresent());
    }

    @Test public void stopAllSelectsOnlyCliOwnedRecords() throws Exception {
        Fixture fixture = new Fixture();
        fixture.register("external", fixture.clock.instant(), "/log");
        fixture.processes.put(314L, fixture.process);
        var result = fixture.supervisor().stop(new StopRequest(null, true, true, Duration.ofSeconds(1)));
        assertTrue(result.complete());
        assertEquals(List.of(), result.items());
        assertFalse(fixture.process.destroyCalled);
    }

    @Test public void statusProducesStartingReadyDegradedAndStaleStates() throws Exception {
        Fixture fixture = new Fixture();
        String ready = "11111111-2222-3333-4444-555555555551";
        String starting = "11111111-2222-3333-4444-555555555552";
        String degraded = "11111111-2222-3333-4444-555555555553";
        String stale = "11111111-2222-3333-4444-555555555554";
        fixture.writeRecord(ready, 401, 9101, fixture.clock.instant().minusSeconds(60), "cli", null);
        fixture.writeRecord(starting, 402, 9102, fixture.clock.instant(), "cli", null);
        fixture.writeRecord(degraded, 403, 9103, fixture.clock.instant().minusSeconds(60), "cli", null);
        fixture.writeRecord(stale, 404, 9104, fixture.clock.instant().minusSeconds(60), "cli", null);
        fixture.processes.put(401L, new FakeProcess(401, ready));
        fixture.processes.put(402L, new FakeProcess(402, starting));
        fixture.processes.put(403L, new FakeProcess(403, degraded));
        fixture.probe = uri -> uri.getPort() == 9101 ? new ProbeResult(true, 200, "HTTP 200")
                : new ProbeResult(false, 503, "HTTP 503");

        List<StatusItem> statuses = fixture.supervisor().statusAll();
        Map<String, String> states = new HashMap<>();
        statuses.forEach(value -> states.put(value.instance().instanceId(), value.state()));
        assertEquals("ready", states.get(ready));
        assertEquals("starting", states.get(starting));
        assertEquals("degraded", states.get(degraded));
        assertEquals("stale", states.get(stale));
    }

    @Test public void hostOverwriteWithoutLogFileRemainsVisibleAndStoppable() throws Exception {
        Fixture fixture = new Fixture();
        Path recordPath = Path.of("/registry/" + ID + ".json");
        fixture.files.values.put(recordPath, """
                {"schemaVersion":1,"instanceId":"11111111-2222-3333-4444-555555555555",\
                "pid":314,"port":9123,"baseUrl":"http://127.0.0.1:9123",\
                "workspace":"/workspace","edtHome":"/edt","mode":"headless","owner":"cli",\
                "startedAt":"2026-08-18T07:00:00Z","nonce":"host-publisher"}
                """);
        fixture.process.commandLine = "java -Dcodepilot.instance.id=" + ID;
        fixture.processes.put(314L, fixture.process);
        fixture.probe = uri -> new ProbeResult(true, 200, "HTTP 200");

        assertEquals("ready", fixture.supervisor().statusAll().get(0).state());
        assertTrue(fixture.supervisor().stop(new StopRequest(ID, false, false, Duration.ofSeconds(1))).complete());
        assertFalse(fixture.files.values.containsKey(recordPath));
    }

    private static final class Fixture {
        final TestHost host = new TestHost();
        final InstanceRegistryTest.MemoryFiles files = new InstanceRegistryTest.MemoryFiles();
        final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T07:00:00Z"));
        final Map<Long, ProcessHandleFacade> processes = new HashMap<>();
        final FakeProcess process = new FakeProcess(314, ID);
        final List<URI> shutdownRequests = new ArrayList<>();
        List<String> launchedCommand = List.of();
        Path launchedStdout;
        Path launchedStderr;
        int waitCalls;
        boolean portAvailable = true;
        com.codepilot1c.cli.EndpointProbe probe = uri -> new ProbeResult(true, 200, "HTTP 200");

        Fixture() {
            host.directories.add("/edt");
            host.files.add("/edt/1cedtcli");
            host.directories.add("/workspace");
        }

        InstanceRegistry registry() { return new InstanceRegistry(files, Path.of("/registry")); }

        EdtSupervisor supervisor() {
            ProcessLauncher launcher = (command, stdout, stderr) -> {
                launchedCommand = List.copyOf(command);
                launchedStdout = stdout;
                launchedStderr = stderr;
                process.commandLine = String.join(" ", command);
                processes.put(process.pid(), process);
                return process;
            };
            WaitStrategy wait = duration -> { waitCalls++; clock.advance(duration); };
            return new EdtSupervisor(new EdtInstallationDiscovery(host), files, registry(), launcher,
                    pid -> Optional.ofNullable(processes.get(pid)), ignored -> portAvailable, probe,
                    shutdownRequests::add, clock, wait, () -> UUID.fromString(ID), Path.of("/logs"));
        }

        void register(String owner, Instant startedAt, String logFile) throws Exception {
            writeRecord(ID, 314, 9123, startedAt, owner, logFile);
        }

        void writeRecord(String id, long pid, int port, Instant startedAt, String owner, String logFile) throws Exception {
            registry().write(new InstanceRecord(1, id, pid, port, "http://127.0.0.1:" + port,
                    "/workspace", "/edt", "headless", owner, startedAt, null, null, logFile));
        }
    }

    private static final class FakeProcess implements ProcessHandleFacade {
        final long pid;
        boolean alive = true;
        boolean destroyStops = true;
        boolean destroyCalled;
        boolean forceCalled;
        String commandLine;
        FakeProcess(long pid, String instanceId) {
            this.pid = pid;
            this.commandLine = "java -Dcodepilot.instance.id=" + instanceId;
        }
        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive; }
        @Override public boolean destroy() { destroyCalled = true; if (destroyStops) alive = false; return true; }
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
