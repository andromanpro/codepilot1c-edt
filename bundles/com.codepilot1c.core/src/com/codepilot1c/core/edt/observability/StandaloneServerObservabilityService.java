package com.codepilot1c.core.edt.observability;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.eclipse.wst.server.core.IServer;

import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;

public class StandaloneServerObservabilityService {

    public record Result(List<StandaloneServerStatus> servers, List<String> lastErrors) {
        public Result {
            servers = List.copyOf(servers == null ? List.of() : servers);
            lastErrors = List.copyOf(lastErrors == null ? List.of() : lastErrors);
        }
    }

    private final EdtRuntimeGateway gateway;
    private final OneCProcessInspectionService processInspectionService;
    private final InfobaseLockService lockService;

    public StandaloneServerObservabilityService() {
        this(new EdtRuntimeGateway(), new OneCProcessInspectionService(), new InfobaseLockService());
    }

    public StandaloneServerObservabilityService(EdtRuntimeGateway gateway,
            OneCProcessInspectionService processInspectionService, InfobaseLockService lockService) {
        this.gateway = gateway == null ? new EdtRuntimeGateway() : gateway;
        this.processInspectionService = processInspectionService == null
                ? new OneCProcessInspectionService()
                : processInspectionService;
        this.lockService = lockService == null ? new InfobaseLockService() : lockService;
    }

    public Result inspect() {
        List<String> errors = new java.util.ArrayList<>();
        IStandaloneServerService service;
        try {
            service = gateway.peekStandaloneServerService();
        } catch (Exception | NoSuchMethodError e) {
            errors.add("peekStandaloneServerService failed: " + message(e)); //$NON-NLS-1$
            return new Result(List.of(), errors);
        }
        if (service == null) {
            errors.add("IStandaloneServerService is unavailable"); //$NON-NLS-1$
            return new Result(List.of(), errors);
        }

        List<IServer> servers;
        try {
            servers = service.getServers();
        } catch (Exception | NoSuchMethodError e) {
            errors.add("getServers failed: " + message(e)); //$NON-NLS-1$
            return new Result(List.of(), errors);
        }
        if (servers == null || servers.isEmpty()) {
            return new Result(List.of(), errors);
        }

        List<OneCProcessSnapshot> processes = safeInspectProcesses(errors);
        List<StandaloneServerStatus> statuses = servers.stream()
                .filter(Objects::nonNull)
                .map(server -> inspectServer(service, server, processes))
                .sorted(Comparator.comparing(StandaloneServerStatus::serverName))
                .toList();
        return new Result(statuses, errors);
    }

    private StandaloneServerStatus inspectServer(IStandaloneServerService service, IServer server,
            List<OneCProcessSnapshot> processes) {
        List<String> errors = new java.util.ArrayList<>();
        String serverName = safeServerName(server, errors);
        String state = safeState(server, errors);
        String configPath = safePath(() -> service.getServerLocation(server), "getServerLocation", errors); //$NON-NLS-1$
        String infobasePath = safePath(() -> service.getServerDataLocation(server), "getServerDataLocation", errors); //$NON-NLS-1$
        List<OneCProcessSnapshot> related = relatedProcesses(serverName, configPath, infobasePath, processes);
        long pid = related.stream().mapToLong(OneCProcessSnapshot::pid).findFirst().orElse(0L);
        List<Integer> ports = related.stream()
                .flatMap(process -> process.ports().stream())
                .distinct()
                .sorted()
                .toList();
        boolean designerOrImportSession = related.stream().anyMatch(StandaloneServerObservabilityService::isDesignerOrImport);
        List<InfobaseLockSnapshot> locks = inspectLocks(infobasePath, errors);
        return new StandaloneServerStatus(serverName, state, pid, ports, configPath, infobasePath,
                false, 0, designerOrImportSession, related, locks, errors);
    }

    private List<OneCProcessSnapshot> safeInspectProcesses(List<String> errors) {
        try {
            return processInspectionService.inspect();
        } catch (Exception e) {
            errors.add("process inspection failed: " + message(e)); //$NON-NLS-1$
            return List.of();
        }
    }

    private List<OneCProcessSnapshot> relatedProcesses(String serverName, String configPath, String infobasePath,
            List<OneCProcessSnapshot> processes) {
        Set<OneCProcessSnapshot> result = new LinkedHashSet<>();
        for (OneCProcessSnapshot process : processes) {
            if (matches(process, serverName, configPath, infobasePath)) {
                result.add(process);
            }
        }
        return result.stream()
                .sorted(Comparator.comparingLong(OneCProcessSnapshot::pid))
                .toList();
    }

    private static boolean matches(OneCProcessSnapshot process, String serverName, String configPath,
            String infobasePath) {
        String command = process.commandLine();
        if (!infobasePath.isBlank()
                && (command.contains(infobasePath)
                        || process.infobasePaths().stream().anyMatch(path -> samePathText(path, infobasePath)))) {
            return true;
        }
        if (!configPath.isBlank() && command.contains(configPath)) {
            return true;
        }
        return !serverName.isBlank() && command.contains(serverName);
    }

    private List<InfobaseLockSnapshot> inspectLocks(String infobasePath, List<String> errors) {
        if (infobasePath == null || infobasePath.isBlank()) {
            return List.of();
        }
        try {
            return List.of(lockService.inspect(infobasePath));
        } catch (Exception e) {
            errors.add("lock inspection failed: " + message(e)); //$NON-NLS-1$
            return List.of();
        }
    }

    private static String safeServerName(IServer server, List<String> errors) {
        try {
            String name = server.getName();
            return name == null ? "" : name; //$NON-NLS-1$
        } catch (Exception | NoSuchMethodError e) {
            errors.add("getName failed: " + message(e)); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    private static String safeState(IServer server, List<String> errors) {
        try {
            return stateName(server.getServerState());
        } catch (Exception | NoSuchMethodError e) {
            errors.add("getServerState failed: " + message(e)); //$NON-NLS-1$
            return "unknown"; //$NON-NLS-1$
        }
    }

    private static String safePath(PathSupplier supplier, String operation, List<String> errors) {
        try {
            Path path = supplier.get();
            return path == null ? "" : path.toString(); //$NON-NLS-1$
        } catch (Exception | NoSuchMethodError e) {
            errors.add(operation + " failed: " + message(e)); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    private static String stateName(int state) {
        return switch (state) {
            case IServer.STATE_STARTED -> "started"; //$NON-NLS-1$
            case IServer.STATE_STARTING -> "starting"; //$NON-NLS-1$
            case IServer.STATE_STOPPED -> "stopped"; //$NON-NLS-1$
            case IServer.STATE_STOPPING -> "stopping"; //$NON-NLS-1$
            default -> "unknown"; //$NON-NLS-1$
        };
    }

    private static boolean isDesignerOrImport(OneCProcessSnapshot process) {
        String command = process.commandLine().toLowerCase(Locale.ROOT);
        return "designer_session".equals(process.processType()) //$NON-NLS-1$
                || command.contains(" designer ") //$NON-NLS-1$
                || command.contains("/import") //$NON-NLS-1$
                || command.contains(" import ") //$NON-NLS-1$
                || command.contains("/loadcfg") //$NON-NLS-1$
                || command.contains(" loadcfg "); //$NON-NLS-1$
    }

    private static boolean samePathText(String left, String right) {
        return stripTrailingSeparator(left).equals(stripTrailingSeparator(right));
    }

    private static String stripTrailingSeparator(String value) {
        String result = value == null ? "" : value; //$NON-NLS-1$
        while (result.length() > 1 && (result.endsWith("/") || result.endsWith("\\"))) { //$NON-NLS-1$ //$NON-NLS-2$
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private interface PathSupplier {
        Path get();
    }
}
