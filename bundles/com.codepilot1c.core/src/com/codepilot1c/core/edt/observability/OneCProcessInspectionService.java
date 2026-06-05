package com.codepilot1c.core.edt.observability;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OneCProcessInspectionService {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern PS_ROW = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(\\S+)\\s+(.*)$"); //$NON-NLS-1$
    private static final Pattern LSOF_PID = Pattern.compile("^\\S+\\s+(\\d+)\\s+\\S+\\s+.*$"); //$NON-NLS-1$
    private static final Pattern LISTEN_PORT = Pattern.compile(":(\\d+)\\s+\\(LISTEN\\)"); //$NON-NLS-1$

    private final EdtObservabilityGateway gateway;
    private final CommandRunner runner;

    public OneCProcessInspectionService() {
        this(new EdtObservabilityGateway(), CommandRunner.processBuilder());
    }

    public OneCProcessInspectionService(EdtObservabilityGateway gateway, CommandRunner runner) {
        this.gateway = gateway == null ? new EdtObservabilityGateway() : gateway;
        this.runner = runner == null ? CommandRunner.processBuilder() : runner;
    }

    public List<OneCProcessSnapshot> inspect() {
        return inspect(null);
    }

    public List<OneCProcessSnapshot> inspect(Path knownPath) {
        Map<Long, SnapshotBuilder> builders = new LinkedHashMap<>();
        for (ProcessHandle handle : gateway.allProcesses()) {
            Optional<OneCProcessSnapshot> snapshot = fromProcessHandle(handle);
            snapshot.filter(OneCProcessInspectionService::isRelevant).ifPresent(value -> builders
                    .computeIfAbsent(Long.valueOf(value.pid()), ignored -> new SnapshotBuilder(value.pid()))
                    .merge(value));
        }

        CommandResult psResult = runner.run(List.of("ps", "-axo", "pid,ppid,user,command"), COMMAND_TIMEOUT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!psResult.timedOut()) {
            for (String line : psResult.stdout().lines().toList()) {
                parsePsRow(line).filter(OneCProcessInspectionService::isRelevant).ifPresent(value -> builders
                        .computeIfAbsent(Long.valueOf(value.pid()), ignored -> new SnapshotBuilder(value.pid()))
                        .merge(value));
            }
        }

        enrichPorts(builders);
        if (knownPath != null) {
            enrichOpenFiles(builders, knownPath);
        }
        Map<Long, List<Long>> children = childrenByParent(builders.values());
        return builders.values().stream()
                .map(builder -> builder.build(children.getOrDefault(Long.valueOf(builder.pid), List.of())))
                .filter(OneCProcessInspectionService::isRelevant)
                .sorted(Comparator.comparingLong(OneCProcessSnapshot::pid))
                .toList();
    }

    public static OneCProcessSnapshot classify(long pid, long ppid, String user, String commandLine) {
        String command = commandLine == null ? "" : commandLine.strip(); //$NON-NLS-1$
        String processName = processName(command);
        String processType = classifyType(processName, command);
        return new OneCProcessSnapshot(pid, ppid, user, processName, command, processType,
                extractInfobasePaths(command, processType), List.of(), List.of());
    }

    static Optional<OneCProcessSnapshot> parsePsRow(String line) {
        if (line == null || line.isBlank()
                || line.stripLeading().toLowerCase(Locale.ROOT).startsWith("pid ")) { //$NON-NLS-1$
            return Optional.empty();
        }
        Matcher matcher = PS_ROW.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(classify(
                parseLong(matcher.group(1), -1L),
                parseLong(matcher.group(2), -1L),
                matcher.group(3),
                matcher.group(4)));
    }

    private Optional<OneCProcessSnapshot> fromProcessHandle(ProcessHandle handle) {
        if (handle == null) {
            return Optional.empty();
        }
        ProcessHandle.Info info = handle.info();
        String commandLine = info.commandLine().orElseGet(() -> buildCommandLine(info));
        return Optional.of(classify(
                handle.pid(),
                handle.parent().map(ProcessHandle::pid).orElse(-1L),
                info.user().orElse(""), //$NON-NLS-1$
                commandLine));
    }

    private void enrichPorts(Map<Long, SnapshotBuilder> builders) {
        CommandResult result = runner.run(List.of("lsof", "-nP", "-iTCP", "-sTCP:LISTEN"), COMMAND_TIMEOUT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (result.timedOut() || result.stdout().isBlank()) {
            return;
        }
        for (String line : result.stdout().lines().toList()) {
            Matcher pidMatcher = LSOF_PID.matcher(line);
            Matcher portMatcher = LISTEN_PORT.matcher(line);
            if (!pidMatcher.matches() || !portMatcher.find()) {
                continue;
            }
            long pid = parseLong(pidMatcher.group(1), -1L);
            int port = (int) parseLong(portMatcher.group(1), -1L);
            SnapshotBuilder builder = builders.get(Long.valueOf(pid));
            if (builder != null && port > 0) {
                builder.ports.add(Integer.valueOf(port));
            }
        }
    }

    private void enrichOpenFiles(Map<Long, SnapshotBuilder> builders, Path knownPath) {
        CommandResult result = runner.run(List.of("lsof", "-nP", knownPath.toString()), COMMAND_TIMEOUT); //$NON-NLS-1$ //$NON-NLS-2$
        if (result.timedOut() || result.stdout().isBlank()) {
            return;
        }
        for (String line : result.stdout().lines().toList()) {
            Matcher matcher = LSOF_PID.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            long pid = parseLong(matcher.group(1), -1L);
            SnapshotBuilder builder = builders.get(Long.valueOf(pid));
            if (builder != null) {
                builder.infobasePaths.add(knownPath.toString());
            }
        }
    }

    private static Map<Long, List<Long>> childrenByParent(Iterable<SnapshotBuilder> builders) {
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (SnapshotBuilder builder : builders) {
            result.computeIfAbsent(Long.valueOf(builder.ppid), ignored -> new ArrayList<>())
                    .add(Long.valueOf(builder.pid));
        }
        for (List<Long> pids : result.values()) {
            pids.sort(Comparator.naturalOrder());
        }
        return result;
    }

    private static boolean isRelevant(OneCProcessSnapshot snapshot) {
        return snapshot != null && !"unknown".equals(snapshot.processType()); //$NON-NLS-1$
    }

    private static String buildCommandLine(ProcessHandle.Info info) {
        String command = info.command().orElse(""); //$NON-NLS-1$
        String[] arguments = info.arguments().orElse(new String[0]);
        if (arguments.length == 0) {
            return command;
        }
        return command + " " + String.join(" ", arguments); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String processName(String commandLine) {
        String first = firstToken(commandLine);
        int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        return slash >= 0 ? first.substring(slash + 1) : first;
    }

    private static String classifyType(String processName, String commandLine) {
        String name = processName == null ? "" : processName.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        String command = commandLine == null ? "" : commandLine.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (name.contains("ibsrv") || command.contains("/ibsrv") || command.contains("\\ibsrv")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "ibsrv"; //$NON-NLS-1$
        }
        if (name.equals("ibcmd") || command.contains("/ibcmd") || command.contains("\\ibcmd")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "ibcmd"; //$NON-NLS-1$
        }
        if (command.contains("standaloneserver") || command.contains("standalone-server")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "edt_standalone_server"; //$NON-NLS-1$
        }
        boolean oneCClient = name.equals("1cv8") || name.equals("1cv8.exe") //$NON-NLS-1$ //$NON-NLS-2$
                || name.equals("1cv8c") || name.equals("1cv8c.exe"); //$NON-NLS-1$ //$NON-NLS-2$
        if (oneCClient && hasCommandToken(commandLine, "DESIGNER")) { //$NON-NLS-1$
            return "designer_session"; //$NON-NLS-1$
        }
        if (oneCClient) {
            return "session"; //$NON-NLS-1$
        }
        return "unknown"; //$NON-NLS-1$
    }

    private static List<String> extractInfobasePaths(String commandLine, String processType) {
        Set<String> paths = new LinkedHashSet<>();
        extractFileBasePath(commandLine).ifPresent(paths::add);
        if ("ibsrv".equals(processType)) { //$NON-NLS-1$
            List<String> tokens = tokenize(commandLine);
            if (tokens.size() > 1) {
                paths.add(stripQuotes(tokens.get(1)));
            }
        }
        return List.copyOf(paths);
    }

    static Optional<String> extractFileBasePath(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String upper = text.toUpperCase(Locale.ROOT);
        int start = -1;
        for (int index = upper.indexOf("/F"); index >= 0; index = upper.indexOf("/F", index + 2)) { //$NON-NLS-1$ //$NON-NLS-2$
            if (isFileBaseFlag(text, index)) {
                start = index + 2;
                break;
            }
        }
        if (start < 0) {
            return Optional.empty();
        }
        int index = start;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index >= text.length()) {
            return Optional.empty();
        }
        char quote = text.charAt(index);
        boolean quoted = quote == '"' || quote == '\'';
        if (quoted) {
            index++;
        }
        int end = index;
        while (end < text.length()) {
            char current = text.charAt(end);
            if (quoted ? current == quote : Character.isWhitespace(current) || current == ';') {
                break;
            }
            end++;
        }
        String path = stripQuotes(text.substring(index, end).strip());
        return path.isBlank() ? Optional.empty() : Optional.of(path);
    }

    private static boolean isFileBaseFlag(String text, int index) {
        boolean startsSegment = index == 0 || Character.isWhitespace(text.charAt(index - 1))
                || text.charAt(index - 1) == ';';
        if (!startsSegment) {
            return false;
        }
        int nextIndex = index + 2;
        if (nextIndex >= text.length()) {
            return true;
        }
        char next = text.charAt(nextIndex);
        if (Character.isWhitespace(next) || next == '"' || next == '\'' || next == '/' || next == '\\') {
            return true;
        }
        return Character.isLetter(next) && nextIndex + 1 < text.length() && text.charAt(nextIndex + 1) == ':';
    }

    static boolean hasCommandToken(String commandLine, String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return false;
        }
        for (String token : tokenize(commandLine)) {
            if (stripQuotes(token).equalsIgnoreCase(expectedToken)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokenize(String commandLine) {
        List<String> result = new ArrayList<>();
        if (commandLine == null || commandLine.isBlank()) {
            return result;
        }
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < commandLine.length(); i++) {
            char current = commandLine.charAt(i);
            if (quoted) {
                if (current == quote) {
                    quoted = false;
                } else {
                    token.append(current);
                }
            } else if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
            } else if (Character.isWhitespace(current)) {
                if (token.length() > 0) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(current);
            }
        }
        if (token.length() > 0) {
            result.add(token.toString());
        }
        return result;
    }

    private static String firstToken(String commandLine) {
        List<String> tokens = tokenize(commandLine);
        return tokens.isEmpty() ? "" : stripQuotes(tokens.get(0)); //$NON-NLS-1$
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value; //$NON-NLS-1$
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return (first == last && (first == '"' || first == '\'')) ? value.substring(1, value.length() - 1) : value;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class SnapshotBuilder {
        private final long pid;
        private long ppid = -1L;
        private String user = ""; //$NON-NLS-1$
        private String processName = ""; //$NON-NLS-1$
        private String commandLine = ""; //$NON-NLS-1$
        private String processType = "unknown"; //$NON-NLS-1$
        private final Set<String> infobasePaths = new LinkedHashSet<>();
        private final Set<Integer> ports = new TreeSet<>();

        SnapshotBuilder(long pid) {
            this.pid = pid;
        }

        void merge(OneCProcessSnapshot snapshot) {
            ppid = snapshot.ppid();
            user = snapshot.user();
            processName = snapshot.processName();
            commandLine = snapshot.commandLine();
            processType = snapshot.processType();
            infobasePaths.addAll(snapshot.infobasePaths());
            ports.addAll(snapshot.ports());
        }

        OneCProcessSnapshot build(List<Long> children) {
            return new OneCProcessSnapshot(pid, ppid, user, processName, commandLine, processType,
                    List.copyOf(infobasePaths), List.copyOf(ports), children);
        }
    }
}
