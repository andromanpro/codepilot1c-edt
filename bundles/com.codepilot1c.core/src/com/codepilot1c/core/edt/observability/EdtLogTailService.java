package com.codepilot1c.core.edt.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

public class EdtLogTailService {

    public record Request(
            String project,
            String since,
            String opId,
            long pid,
            String infobase,
            boolean errorsOnly,
            int maxLines) {

        public Request {
            project = normalize(project);
            since = normalize(since);
            opId = normalize(opId);
            infobase = normalize(infobase);
            maxLines = Math.max(1, Math.min(maxLines <= 0 ? DEFAULT_MAX_LINES : maxLines, MAX_LINES_CAP));
        }
    }

    public record Result(Path workspaceRoot, List<Path> sources, List<EdtLogLine> lines) {
        public Result {
            sources = List.copyOf(sources == null ? List.of() : sources);
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public static final int DEFAULT_MAX_LINES = 200;
    public static final int MAX_LINES_CAP = 2_000;

    private static final Pattern OP_ID = Pattern.compile("\\bop[_-]?id=([A-Za-z0-9_.:-]+)\\b"); //$NON-NLS-1$
    private static final Pattern PID = Pattern.compile("\\bpid=(\\d+)\\b"); //$NON-NLS-1$
    private static final Pattern INFOBASE = Pattern.compile("\\binfobase=([^\\s]+)"); //$NON-NLS-1$
    private static final Pattern EDT_ENTRY =
            Pattern.compile("^!ENTRY\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+(.+)$"); //$NON-NLS-1$
    private static final Pattern BRACKET_LEVEL =
            Pattern.compile("^\\s*\\[(ERROR|WARN|INFO|DEBUG|TRACE)\\]\\s+.*$", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$
    private static final DateTimeFormatter EDT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT); //$NON-NLS-1$

    private final Path workspaceRoot;
    private final List<Path> extraSources;

    public EdtLogTailService() {
        this(resolveWorkspaceRoot(), List.of());
    }

    public EdtLogTailService(Path workspaceRoot) {
        this(workspaceRoot, List.of());
    }

    public EdtLogTailService(Path workspaceRoot, List<Path> extraSources) {
        this.workspaceRoot = normalizeRoot(workspaceRoot);
        this.extraSources = List.copyOf(extraSources == null ? List.of() : extraSources);
    }

    public Result tail(Request request) {
        Request effective = request == null
                ? new Request("", "", "", 0L, "", false, DEFAULT_MAX_LINES) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                : request;
        Path root = workspaceRoot;
        if (root == null) {
            return new Result(null, List.of(), List.of());
        }
        Set<Path> sources = discoverSources(root);
        ArrayDeque<EdtLogLine> tail = new ArrayDeque<>();
        Optional<Instant> since = parseSince(effective.since());
        for (Path source : sources) {
            if (!isAllowedSource(root, source) || !Files.isRegularFile(source)) {
                continue;
            }
            List<String> lines = readLines(source);
            for (int i = 0; i < lines.size(); i++) {
                EdtLogLine line = parseLine(root, source, i + 1, lines.get(i));
                if (matches(line, effective, since)) {
                    tail.addLast(line);
                    while (tail.size() > effective.maxLines()) {
                        tail.removeFirst();
                    }
                }
            }
        }
        return new Result(root, List.copyOf(sources), List.copyOf(tail));
    }

    private Set<Path> discoverSources(Path root) {
        Set<Path> result = new LinkedHashSet<>();
        addIfRegular(result, root.resolve(".metadata/.log")); //$NON-NLS-1$
        addTree(result, root.resolve(".codepilot/runs")); //$NON-NLS-1$
        addTree(result, root.resolve(".codepilot/imports")); //$NON-NLS-1$
        for (Path source : extraSources) {
            if (source != null) {
                addIfRegular(result, source);
            }
        }
        return result;
    }

    private static void addTree(Set<Path> result, Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(EdtLogTailService::isLogLike)
                    .sorted(Comparator.naturalOrder())
                    .forEach(result::add);
        } catch (IOException e) {
            // Ignore unreadable diagnostic folders; callers still receive other sources.
        }
    }

    private static void addIfRegular(Set<Path> result, Path path) {
        if (path != null && Files.isRegularFile(path)) {
            result.add(path);
        }
    }

    private static boolean isLogLike(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        return name.endsWith(".log") || name.endsWith(".txt") || name.contains("log"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static List<String> readLines(Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static EdtLogLine parseLine(Path root, Path source, int lineNumber, String text) {
        String line = text == null ? "" : text; //$NON-NLS-1$
        String level = inferLevel(line);
        String timestamp = inferTimestamp(line);
        return new EdtLogLine(
                sourceName(root, source),
                source,
                lineNumber,
                line,
                timestamp,
                level,
                firstMatch(OP_ID, line),
                parseLong(firstMatch(PID, line), 0L),
                firstMatch(INFOBASE, line));
    }

    private static boolean matches(EdtLogLine line, Request request, Optional<Instant> since) {
        String text = line.text();
        if (!request.project().isBlank() && !containsIgnoreCase(text, request.project())
                && !containsIgnoreCase(line.path().toString(), request.project())) {
            return false;
        }
        if (!request.opId().isBlank() && !request.opId().equals(line.opId())
                && !text.contains(request.opId())) {
            return false;
        }
        if (request.pid() > 0L && request.pid() != line.pid()
                && !text.contains("pid=" + request.pid())) { //$NON-NLS-1$
            return false;
        }
        if (!request.infobase().isBlank() && !containsIgnoreCase(text, request.infobase())
                && !containsIgnoreCase(line.infobase(), request.infobase())) {
            return false;
        }
        if (request.errorsOnly() && !isErrorLine(line)) {
            return false;
        }
        return since.isEmpty() || line.timestamp().isBlank()
                || parseLineInstant(line.timestamp()).map(value -> !value.isBefore(since.get())).orElse(true);
    }

    private static boolean isErrorLine(EdtLogLine line) {
        String level = line.level();
        String text = line.text().toLowerCase(Locale.ROOT);
        return "error".equals(level) //$NON-NLS-1$
                || text.contains("error") //$NON-NLS-1$
                || text.contains("exception") //$NON-NLS-1$
                || text.contains("failed"); //$NON-NLS-1$
    }

    private static String inferLevel(String line) {
        Matcher entry = EDT_ENTRY.matcher(line);
        if (entry.matches()) {
            return switch (entry.group(1)) {
                case "4" -> "error"; //$NON-NLS-1$ //$NON-NLS-2$
                case "2" -> "warning"; //$NON-NLS-1$ //$NON-NLS-2$
                case "1" -> "info"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> ""; //$NON-NLS-1$
            };
        }
        Matcher bracket = BRACKET_LEVEL.matcher(line);
        if (bracket.matches()) {
            String level = bracket.group(1).toLowerCase(Locale.ROOT);
            return "warn".equals(level) ? "warning" : level; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ""; //$NON-NLS-1$
    }

    private static String inferTimestamp(String line) {
        Matcher entry = EDT_ENTRY.matcher(line);
        return entry.matches() ? entry.group(2).strip() : ""; //$NON-NLS-1$
    }

    private static Optional<Instant> parseSince(String since) {
        if (since == null || since.isBlank()) {
            return Optional.empty();
        }
        return parseLineInstant(since);
    }

    private static Optional<Instant> parseLineInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(LocalDateTime.parse(value, EDT_TIMESTAMP).toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    private static String sourceName(Path root, Path source) {
        if (root != null && source != null && source.normalize().startsWith(root.normalize())) {
            return root.normalize().relativize(source.normalize()).toString();
        }
        return source == null ? "" : source.toString(); //$NON-NLS-1$
    }

    private static String firstMatch(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : ""; //$NON-NLS-1$
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean isAllowedSource(Path root, Path source) {
        if (root == null || source == null) {
            return false;
        }
        try {
            return source.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return source.normalize().startsWith(root.normalize());
        }
    }

    private static Path normalizeRoot(Path root) {
        return root == null ? null : root.toAbsolutePath().normalize();
    }

    private static Path resolveWorkspaceRoot() {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
            return root == null || root.getLocation() == null ? null : root.getLocation().toFile().toPath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip(); //$NON-NLS-1$
    }
}
