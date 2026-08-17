package com.codepilot1c.core.java.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.codepilot1c.core.edt.observability.CommandResult;
import com.codepilot1c.core.edt.observability.CommandRunner;

/**
 * Runs a compile-only probe in an external javac process. The process boundary
 * limits compiler resources; source instructions are never executed.
 */
public final class JavaCompileProbeRunner {

    private static final Logger LOG = Logger.getLogger(JavaCompileProbeRunner.class.getName());

    public static final int MAX_SNIPPET_CHARS = 20_000;
    public static final int MAX_DIAGNOSTICS_CHARS = 32 * 1024;
    public static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    private final CommandRunner commandRunner;
    private final JdkLocator jdkLocator;
    private final List<Path> forbiddenRoots;

    public JavaCompileProbeRunner(CommandRunner commandRunner, JdkLocator jdkLocator) {
        this(commandRunner, jdkLocator, List.of(Path.of("").toAbsolutePath().normalize())); //$NON-NLS-1$
    }

    public JavaCompileProbeRunner(CommandRunner commandRunner, JdkLocator jdkLocator,
            List<Path> forbiddenRoots) {
        this.commandRunner = commandRunner;
        this.jdkLocator = jdkLocator;
        this.forbiddenRoots = forbiddenRoots == null ? List.of() : forbiddenRoots.stream()
                .filter(path -> path != null)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    public ProbeOutcome run(boolean enabled, String snippet, SnippetKind requestedKind) {
        if (!enabled) {
            return ProbeOutcome.failure("probe_disabled", "Java compile probe is disabled", "none"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (snippet == null || snippet.isBlank()) {
            return ProbeOutcome.failure("snippet_blank", "Snippet must not be blank", "none"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (snippet.length() > MAX_SNIPPET_CHARS) {
            return ProbeOutcome.failure("snippet_too_large", //$NON-NLS-1$
                    "Snippet exceeds 20000 characters", "none"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        JdkLocator.Location location = jdkLocator.locate();
        if (!location.available()) {
            String diagnostics = "javac not usable; checked " + location.checkedSources(); //$NON-NLS-1$
            return ProbeOutcome.failure(location.errorCode(), diagnostics, location.source());
        }

        SnippetKind kind = requestedKind == null ? SnippetKind.AUTO : requestedKind;
        long started = System.nanoTime();
        Path tempDir = null;
        try {
            tempDir = createTempDirectory();
            Path sourceFile = tempDir.resolve("Probe.java"); //$NON-NLS-1$
            Path outDir = Files.createDirectory(tempDir.resolve("out")); //$NON-NLS-1$
            JavacCommandBuilder commandBuilder = new JavacCommandBuilder(location.javac());
            List<SnippetKind> attempts = kind == SnippetKind.AUTO
                    ? SnippetWrapper.AUTO_ORDER : List.of(kind);
            Attempt firstFailure = null;
            for (SnippetKind attemptKind : attempts) {
                SnippetWrapper.WrappedSnippet wrapped = SnippetWrapper.wrap(snippet, attemptKind);
                Files.writeString(sourceFile, wrapped.source(), StandardCharsets.UTF_8);
                CommandResult result = commandRunner.run(
                        commandBuilder.build(sourceFile, outDir), ATTEMPT_TIMEOUT);
                Attempt attempt = mapAttempt(result, sourceFile, wrapped.preludeLines(), attemptKind);
                if (result.timedOut()) {
                    return harnessFailure("timeout", attempt, location.source(), started); //$NON-NLS-1$
                }
                if (result.exitCode() < 0) {
                    return harnessFailure("probe_internal_error", attempt, location.source(), started); //$NON-NLS-1$
                }
                if (result.exitCode() == 0) {
                    return success(attempt, true, attemptKind.name(), location.source(), started);
                }
                if (firstFailure == null) {
                    firstFailure = attempt;
                }
            }
            Attempt reported = firstFailure == null
                    ? new Attempt("", 0, 0, false, -1, kind) : firstFailure; //$NON-NLS-1$
            String reportedKind = kind == SnippetKind.AUTO ? "UNRESOLVED" : kind.name(); //$NON-NLS-1$
            return success(reported, false, reportedKind, location.source(), started);
        } catch (IOException | RuntimeException e) {
            String diagnostics = safeMessage(e);
            if (tempDir != null) {
                diagnostics = diagnostics.replace(tempDir.toString(), "snippet-temp"); //$NON-NLS-1$
            }
            return new ProbeOutcome(false, false, "probe_internal_error", "UNRESOLVED", //$NON-NLS-1$ //$NON-NLS-2$
                    diagnostics, 0, 0, false, elapsedMillis(started), -1,
                    location.source(), ProbeOutcome.COMPILE_ONLY);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private Path createTempDirectory() throws IOException {
        Path tempDir = Files.createTempDirectory("cp1c-javaprobe-").toAbsolutePath().normalize(); //$NON-NLS-1$
        for (Path root : forbiddenRoots) {
            if (tempDir.startsWith(root)) {
                deleteRecursively(tempDir);
                throw new IOException("Temporary directory overlaps a forbidden project/workspace root"); //$NON-NLS-1$
            }
        }
        try {
            Files.setPosixFilePermissions(tempDir, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX platform; the platform temp-directory ACL remains authoritative.
        }
        return tempDir;
    }

    private static Attempt mapAttempt(CommandResult result, Path sourceFile,
            int preludeLines, SnippetKind kind) {
        String raw = joinOutput(result.stderr(), result.stdout());
        boolean truncated = raw.length() > MAX_DIAGNOSTICS_CHARS;
        String capped = truncated ? raw.substring(0, MAX_DIAGNOSTICS_CHARS) : raw;
        DiagnosticsMapper.MappedDiagnostics mapped = DiagnosticsMapper.map(capped, sourceFile, preludeLines);
        return new Attempt(mapped.text(), mapped.errorCount(), mapped.warningCount(),
                truncated, result.exitCode(), kind);
    }

    private static ProbeOutcome success(Attempt attempt, boolean compiles, String snippetKind,
            String jdkSource, long started) {
        return new ProbeOutcome(true, compiles, "", snippetKind, attempt.diagnostics(), //$NON-NLS-1$
                attempt.errorCount(), attempt.warningCount(), attempt.truncated(),
                elapsedMillis(started), attempt.exitCode(), jdkSource, ProbeOutcome.COMPILE_ONLY);
    }

    private static ProbeOutcome harnessFailure(String errorCode, Attempt attempt,
            String jdkSource, long started) {
        return new ProbeOutcome(false, false, errorCode, attempt.kind().name(), attempt.diagnostics(),
                attempt.errorCount(), attempt.warningCount(), attempt.truncated(),
                elapsedMillis(started), -1, jdkSource, ProbeOutcome.COMPILE_ONLY);
    }

    private static String joinOutput(String stderr, String stdout) {
        List<String> parts = new ArrayList<>(2);
        if (stderr != null && !stderr.isBlank()) {
            parts.add(stderr.stripTrailing());
        }
        if (stdout != null && !stdout.isBlank()) {
            parts.add(stdout.stripTrailing());
        }
        return String.join(System.lineSeparator(), parts);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to remove compile-probe temporary path", e); //$NON-NLS-1$
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to traverse compile-probe temporary directory", e); //$NON-NLS-1$
        }
    }

    private record Attempt(String diagnostics, int errorCount, int warningCount,
            boolean truncated, int exitCode, SnippetKind kind) {
    }
}
