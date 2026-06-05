package com.codepilot1c.core.edt.observability;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InfobaseLockService {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern LSOF_PID = Pattern.compile("^\\S+\\s+(\\d+)\\s+\\S+\\s+.*$"); //$NON-NLS-1$
    private static final Pattern CONFIG_COMMAND_WORD =
            Pattern.compile("(^|\\s)(/import|import|/loadcfg|loadcfg)(\\s|$)"); //$NON-NLS-1$
    private static final String DATA_FILE = "1Cv8.1CD"; //$NON-NLS-1$
    private static final String LOCK_FILE = "1Cv8.1CL"; //$NON-NLS-1$

    private final EdtObservabilityGateway gateway;
    private final CommandRunner runner;
    private final OneCProcessInspectionService processInspectionService;

    public InfobaseLockService() {
        this(new EdtObservabilityGateway(), CommandRunner.processBuilder());
    }

    public InfobaseLockService(EdtObservabilityGateway gateway, CommandRunner runner) {
        this(gateway, runner, new OneCProcessInspectionService(gateway, runner));
    }

    public InfobaseLockService(EdtObservabilityGateway gateway, CommandRunner runner,
            OneCProcessInspectionService processInspectionService) {
        this.gateway = gateway == null ? new EdtObservabilityGateway() : gateway;
        this.runner = runner == null ? CommandRunner.processBuilder() : runner;
        this.processInspectionService = processInspectionService == null
                ? new OneCProcessInspectionService(this.gateway, this.runner)
                : processInspectionService;
    }

    public InfobaseLockSnapshot inspect(String pathOrConnection) {
        String input = pathOrConnection == null ? "" : pathOrConnection.strip(); //$NON-NLS-1$
        List<String> evidence = new ArrayList<>();
        if (input.isBlank()) {
            evidence.add("path_or_connection is blank"); //$NON-NLS-1$
            return new InfobaseLockSnapshot(input, "", List.of(), "unknown", 0.0d, evidence, List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String normalized = normalizePathOrConnection(input);
        Path normalizedPath;
        try {
            normalizedPath = Path.of(normalized).normalize();
        } catch (InvalidPathException e) {
            evidence.add("invalid path: " + e.getMessage()); //$NON-NLS-1$
            return new InfobaseLockSnapshot(input, "", List.of(), "unknown", 0.0d, evidence, List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<Path> candidates = candidatePaths(normalizedPath);
        Set<Long> lsofPids = new LinkedHashSet<>();
        List<String> inspectedPaths = new ArrayList<>();
        for (Path candidate : candidates) {
            inspectedPaths.add(candidate.toString());
            CommandResult result = runner.run(List.of("lsof", "-nP", candidate.toString()), COMMAND_TIMEOUT); //$NON-NLS-1$ //$NON-NLS-2$
            evidence.add("lsof " + candidate + " exit=" + result.exitCode() + " timed_out=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + result.timedOut());
            addEvidenceLines(evidence, result.stdout());
            addEvidenceLines(evidence, result.stderr());
            lsofPids.addAll(parseLsofPids(result.stdout()));
        }

        List<OneCProcessSnapshot> processes = relatedProcesses(normalizedPath, lsofPids);
        for (OneCProcessSnapshot process : processes) {
            evidence.add("process pid=" + process.pid() + " type=" + process.processType() + " command=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + process.commandLine());
        }

        LockDecision decision = decide(processes, lsofPids);
        Set<Long> pids = new LinkedHashSet<>(lsofPids);
        processes.stream().map(OneCProcessSnapshot::pid).map(Long::valueOf).forEach(pids::add);
        return new InfobaseLockSnapshot(input, normalizedPath.toString(), inspectedPaths, decision.kind,
                decision.confidence, evidence, pids.stream().sorted().toList(), processes);
    }

    static String normalizePathOrConnection(String pathOrConnection) {
        return OneCProcessInspectionService.extractFileBasePath(pathOrConnection)
                .orElse(stripQuotes(pathOrConnection == null ? "" : pathOrConnection.strip())); //$NON-NLS-1$
    }

    private List<Path> candidatePaths(Path path) {
        Set<Path> result = new LinkedHashSet<>();
        if (isFileArgument(path)) {
            result.add(path);
            Path parent = path.getParent();
            if (parent != null) {
                result.add(parent.resolve(DATA_FILE));
                result.add(parent.resolve(LOCK_FILE));
            }
        } else {
            result.add(path.resolve(DATA_FILE));
            result.add(path.resolve(LOCK_FILE));
        }
        return List.copyOf(result);
    }

    private boolean isFileArgument(Path path) {
        if (path == null) {
            return false;
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString(); //$NON-NLS-1$
        if (DATA_FILE.equalsIgnoreCase(fileName) || LOCK_FILE.equalsIgnoreCase(fileName)) {
            return true;
        }
        return gateway.exists(path) && !gateway.isDirectory(path);
    }

    private List<OneCProcessSnapshot> relatedProcesses(Path normalizedPath, Set<Long> lsofPids) {
        String pathText = normalizedPath.toString();
        return processInspectionService.inspect(normalizedPath).stream()
                .filter(process -> lsofPids.contains(Long.valueOf(process.pid()))
                        || process.infobasePaths().stream().anyMatch(path -> samePathText(path, pathText))
                        || process.commandLine().contains(pathText))
                .sorted(Comparator.comparingLong(OneCProcessSnapshot::pid))
                .toList();
    }

    private static LockDecision decide(List<OneCProcessSnapshot> processes, Set<Long> lsofPids) {
        boolean hasEvidence = !lsofPids.isEmpty() || !processes.isEmpty();
        for (OneCProcessSnapshot process : processes) {
            if (isConfigurationEvidence(process)) {
                return new LockDecision("configuration", 0.9d); //$NON-NLS-1$
            }
        }
        if (hasEvidence) {
            return new LockDecision("session", 0.7d); //$NON-NLS-1$
        }
        return new LockDecision("unknown", 0.1d); //$NON-NLS-1$
    }

    private static boolean isConfigurationEvidence(OneCProcessSnapshot process) {
        String type = process.processType();
        String commandLine = process.commandLine() == null ? "" : process.commandLine(); //$NON-NLS-1$
        String command = commandLine.toLowerCase(Locale.ROOT);
        return "designer_session".equals(type) //$NON-NLS-1$
                || OneCProcessInspectionService.hasCommandToken(commandLine, "DESIGNER") //$NON-NLS-1$
                || CONFIG_COMMAND_WORD.matcher(command).find();
    }

    private static Set<Long> parseLsofPids(String stdout) {
        Set<Long> result = new LinkedHashSet<>();
        if (stdout == null || stdout.isBlank()) {
            return result;
        }
        for (String line : stdout.lines().toList()) {
            Matcher matcher = LSOF_PID.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            try {
                result.add(Long.valueOf(Long.parseLong(matcher.group(1))));
            } catch (NumberFormatException e) {
                // Ignore malformed lsof rows.
            }
        }
        return result;
    }

    private static void addEvidenceLines(List<String> evidence, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        text.lines()
                .filter(line -> !line.isBlank())
                .limit(20)
                .forEach(evidence::add);
    }

    private static boolean samePathText(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return stripTrailingSeparator(left).equals(stripTrailingSeparator(right));
    }

    private static String stripTrailingSeparator(String value) {
        String result = value;
        while (result.length() > 1 && (result.endsWith("/") || result.endsWith("\\"))) { //$NON-NLS-1$ //$NON-NLS-2$
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value; //$NON-NLS-1$
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return (first == last && (first == '"' || first == '\'')) ? value.substring(1, value.length() - 1) : value;
    }

    private record LockDecision(String kind, double confidence) {
    }
}
