/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.codepilot1c.cli.EndpointProbe;
import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.discovery.EdtInstallation;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.HostSystem;

/** Owns CLI-started headless EDT processes and their versioned registry records. */
public final class EdtSupervisor {
    public static final int DEFAULT_PORT = 8765;
    public static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(120);
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration STARTING_WINDOW = Duration.ofSeconds(30);
    private static final Duration MAX_TIMEOUT = Duration.ofDays(1);

    private final EdtInstallationDiscovery discovery;
    private final SupervisorFileSystem files;
    private final InstanceRegistry registry;
    private final ProcessLauncher launcher;
    private final ProcessHandleLookup processLookup;
    private final PortAvailability ports;
    private final EndpointProbe readiness;
    private final ShutdownClient shutdown;
    private final Clock clock;
    private final WaitStrategy wait;
    private final Supplier<UUID> ids;
    private final Path logsDirectory;

    /** Builds production Java SE adapters rooted in the current user's CodePilot directory. */
    public static EdtSupervisor production(HostSystem host, EdtInstallationDiscovery discovery,
            EndpointProbe readiness) {
        DefaultSupervisorFileSystem files = new DefaultSupervisorFileSystem();
        DefaultProcessAccess processes = new DefaultProcessAccess();
        Path root = Path.of(host.userHome(), ".codepilot1c").toAbsolutePath().normalize();
        InstanceRegistry registry = new InstanceRegistry(files, root.resolve("instances"));
        return new EdtSupervisor(discovery, files, registry, processes, processes, PortAvailability.loopback(),
                readiness, ShutdownClient.javaHttpClient(), Clock.systemUTC(), WaitStrategy.threadSleep(),
                UUID::randomUUID, root.resolve("logs"));
    }

    public EdtSupervisor(EdtInstallationDiscovery discovery, SupervisorFileSystem files,
            InstanceRegistry registry, ProcessLauncher launcher, ProcessHandleLookup processLookup,
            PortAvailability ports, EndpointProbe readiness, ShutdownClient shutdown, Clock clock,
            WaitStrategy wait, Supplier<UUID> ids, Path logsDirectory) {
        this.discovery = discovery;
        this.files = files;
        this.registry = registry;
        this.launcher = launcher;
        this.processLookup = processLookup;
        this.ports = ports;
        this.readiness = readiness;
        this.shutdown = shutdown;
        this.clock = clock;
        this.wait = wait;
        this.ids = ids;
        this.logsDirectory = logsDirectory.toAbsolutePath().normalize();
    }

    public StartResult start(StartRequest request) throws SupervisorException {
        if (request.port() < 1 || request.port() > 65535) {
            throw failure(ExitCodes.USAGE, "invalid_port", "port must be between 1 and 65535");
        }
        if (!validTimeout(request.timeout())) {
            throw failure(ExitCodes.USAGE, "invalid_timeout", "timeout must be positive and at most 24 hours");
        }

        Path workspace;
        try { workspace = files.canonicalDirectory(request.workspace()); }
        catch (Exception exception) {
            throw failure(ExitCodes.USAGE, "invalid_workspace", "workspace must be an existing directory");
        }
        if (files.exists(workspace.resolve(".metadata").resolve(".lock"))) {
            throw failure(ExitCodes.EDT_UNAVAILABLE, "workspace_locked", "workspace is already locked by EDT");
        }
        if (!ports.available(request.port())) {
            throw failure(ExitCodes.EDT_UNAVAILABLE, "port_unavailable", "requested loopback port is unavailable");
        }

        EdtInstallation installation = selectInstallation(request.edtHome());
        String instanceId = ids.get().toString();
        Path logFile = logsDirectory.resolve(instanceId + ".log");
        URI baseUri = URI.create("http://127.0.0.1:" + request.port());
        ProcessHandleFacade process;
        try {
            files.createDirectories(logsDirectory);
            files.createDirectories(registry.directory());
            process = launcher.start(buildCommand(installation.launcher(), workspace, request.port(), instanceId,
                    registry.directory()), logFile, logFile);
        } catch (IOException exception) {
            throw failure(ExitCodes.EDT_UNAVAILABLE, "process_start_failed", "unable to start EDT process");
        }

        InstanceRecord record = new InstanceRecord(InstanceRecord.SCHEMA_VERSION, instanceId, process.pid(),
                request.port(), baseUri.toASCIIString(), workspace.toString(), installation.home(), "headless", "cli",
                clock.instant(), null, null, logFile.toString());
        try {
            registry.write(record);
        } catch (IOException exception) {
            terminateFailedStart(process);
            throw failure(ExitCodes.EDT_UNAVAILABLE, "registry_write_failed", "unable to register EDT process");
        }

        Instant deadline = clock.instant().plus(request.timeout());
        while (true) {
            ProbeResult probe = readiness.probe(baseUri);
            if (probe.reachable()) return new StartResult(record, "ready", probe.httpStatus(), probe.detail());
            if (!process.isAlive()) {
                deleteQuietly(record.instanceId());
                throw failure(ExitCodes.EDT_UNAVAILABLE, "process_exited", "EDT exited before becoming ready");
            }
            if (!clock.instant().isBefore(deadline)) {
                terminateFailedStart(process);
                deleteQuietly(record.instanceId());
                throw failure(ExitCodes.EDT_UNAVAILABLE, "readiness_timeout", "EDT did not become ready before timeout");
            }
            try { wait.pause(POLL_INTERVAL); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                terminateFailedStart(process);
                deleteQuietly(record.instanceId());
                throw failure(ExitCodes.EDT_UNAVAILABLE, "start_interrupted", "EDT startup was interrupted");
            }
        }
    }

    public StopResult stop(StopRequest request) throws SupervisorException {
        if (!validTimeout(request.timeout())) {
            throw failure(ExitCodes.USAGE, "invalid_timeout", "timeout must be positive and at most 24 hours");
        }
        if (!request.all() && (request.instanceId() == null || request.instanceId().isBlank())) {
            throw failure(ExitCodes.USAGE, "instance_required", "specify --id or --all");
        }
        if (!request.all() && !validInstanceId(request.instanceId())) {
            throw failure(ExitCodes.USAGE, "invalid_instance_id", "instance id must be a UUID");
        }
        List<InstanceRecord> targets;
        try {
            if (request.all()) targets = registry.list().stream().filter(value -> "cli".equals(value.owner())).toList();
            else targets = List.of(registry.find(request.instanceId()).orElseThrow(() ->
                    failure(ExitCodes.EDT_UNAVAILABLE, "instance_not_found", "instance is not registered")));
        } catch (IllegalArgumentException exception) {
            throw failure(ExitCodes.FAILURE, "registry_record_invalid", "instance registry record is invalid");
        } catch (IOException exception) {
            throw failure(ExitCodes.FAILURE, "registry_read_failed", "unable to read instance registry");
        }

        List<StopItem> items = new ArrayList<>();
        boolean complete = true;
        for (InstanceRecord record : targets) {
            if (!"cli".equals(record.owner())) {
                items.add(new StopItem(record.instanceId(), record.pid(), "not_owned", false));
                complete = false;
                continue;
            }
            Optional<ProcessHandleFacade> found = processLookup.find(record.pid());
            if (found.isEmpty() || !found.orElseThrow().isAlive()) {
                if (delete(record.instanceId())) items.add(new StopItem(record.instanceId(), record.pid(), "stale_removed", true));
                else { items.add(new StopItem(record.instanceId(), record.pid(), "registry_error", false)); complete = false; }
                continue;
            }

            ProcessHandleFacade process = found.orElseThrow();
            if (!matches(process, record.instanceId())) {
                items.add(new StopItem(record.instanceId(), record.pid(), "identity_mismatch", false));
                complete = false;
                continue;
            }
            shutdown.request(URI.create(record.baseUrl()));
            process.destroy();
            boolean stopped = awaitExit(process, request.timeout());
            if (!stopped && request.force()) {
                process.destroyForcibly();
                stopped = awaitExit(process, request.timeout());
            }
            if (stopped && delete(record.instanceId())) {
                items.add(new StopItem(record.instanceId(), record.pid(), "stopped", true));
            } else {
                items.add(new StopItem(record.instanceId(), record.pid(), stopped ? "registry_error" : "still_running", false));
                complete = false;
            }
        }
        return new StopResult(complete, items);
    }

    public List<StatusItem> statusAll() throws SupervisorException {
        List<InstanceRecord> records;
        try { records = registry.list(); }
        catch (IOException exception) {
            throw failure(ExitCodes.FAILURE, "registry_read_failed", "unable to read instance registry");
        }
        List<StatusItem> result = new ArrayList<>();
        for (InstanceRecord record : records) {
            Optional<ProcessHandleFacade> process = processLookup.find(record.pid());
            boolean alive = process.isPresent() && process.orElseThrow().isAlive()
                    && matches(process.orElseThrow(), record.instanceId());
            if (!alive) {
                result.add(new StatusItem(record, "stale", false, 0, "process_not_running"));
                continue;
            }
            ProbeResult probe = readiness.probe(URI.create(record.baseUrl()));
            String state;
            if (probe.reachable()) state = "ready";
            else if (clock.instant().isBefore(record.startedAt().plus(STARTING_WINDOW))) state = "starting";
            else state = "degraded";
            result.add(new StatusItem(record, state, true, probe.httpStatus(), probe.detail()));
        }
        return result;
    }

    public static List<String> buildCommand(String launcher, Path workspace, int port, String instanceId,
            Path registryDirectory) {
        return List.of(launcher, "-nosplash", "-application", "com.codepilot1c.core.headless", "-data",
                workspace.toString(), "-vmargs", "-Dcodepilot.mcp.enabled=true",
                "-Dcodepilot.mcp.host.enabled=true", "-Dcodepilot.mcp.host.http.enabled=true",
                "-Dcodepilot.mcp.host.http.bindAddress=127.0.0.1",
                "-Dcodepilot.mcp.host.http.port=" + port, "-Dcodepilot.instance.id=" + instanceId,
                "-Dcodepilot.instance.owner=cli", "-Dcodepilot.instance.registryDir=" + registryDirectory);
    }

    private EdtInstallation selectInstallation(String explicitHome) throws SupervisorException {
        Optional<EdtInstallation> selected = explicitHome == null || explicitHome.isBlank()
                ? discovery.preferred() : discovery.validateHome(explicitHome);
        return selected.orElseThrow(() -> failure(ExitCodes.EDT_UNAVAILABLE, "edt_not_found",
                "no validated EDT installation with 1cedtcli/1cedt launcher was found"));
    }

    private void terminateFailedStart(ProcessHandleFacade process) {
        if (!process.isAlive()) return;
        process.destroy();
        if (!awaitExit(process, DEFAULT_STOP_TIMEOUT) && process.isAlive()) {
            process.destroyForcibly();
            awaitExit(process, DEFAULT_STOP_TIMEOUT);
        }
    }

    private boolean awaitExit(ProcessHandleFacade process, Duration timeout) {
        Instant deadline = clock.instant().plus(timeout);
        while (process.isAlive() && clock.instant().isBefore(deadline)) {
            try { wait.pause(POLL_INTERVAL); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return false; }
        }
        return !process.isAlive();
    }

    private boolean delete(String id) {
        try { registry.delete(id); return true; }
        catch (IOException | IllegalArgumentException exception) { return false; }
    }

    private void deleteQuietly(String id) { delete(id); }

    private static boolean matches(ProcessHandleFacade process, String instanceId) {
        String marker = "-Dcodepilot.instance.id=" + instanceId;
        return process.commandLine().map(value -> List.of(value.split("\\s+")).contains(marker)).orElse(false);
    }

    private static SupervisorException failure(int exitCode, String error, String message) {
        return new SupervisorException(exitCode, error, message);
    }

    private static boolean validTimeout(Duration timeout) {
        return timeout != null && !timeout.isZero() && !timeout.isNegative() && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }

    private static boolean validInstanceId(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (RuntimeException exception) { return false; }
    }

    public record StartRequest(String workspace, String edtHome, int port, Duration timeout) { }
    public record StartResult(InstanceRecord instance, String state, int httpStatus, String detail) { }
    public record StopRequest(String instanceId, boolean all, boolean force, Duration timeout) { }
    public record StopItem(String instanceId, long pid, String state, boolean stopped) { }
    public record StopResult(boolean complete, List<StopItem> items) { }
    public record StatusItem(InstanceRecord instance, String state, boolean pidAlive, int httpStatus, String detail) { }
}
