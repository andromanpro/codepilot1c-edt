package com.codepilot1c.core.java.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;

import com.codepilot1c.core.edt.observability.CommandResult;
import com.codepilot1c.core.edt.observability.CommandRunner;

import java.time.Duration;

/** Resolves javac from explicitly injected preference, environment, and runtime sources. */
public final class JdkLocator {

    private static final ConcurrentMap<Path, VersionStatus> VERSION_CACHE = new ConcurrentHashMap<>();

    private final Supplier<String> preferenceHome;
    private final Supplier<String> environmentHome;
    private final Supplier<String> runtimeHome;
    private final Function<Path, VersionStatus> versionCheck;

    public JdkLocator(Supplier<String> preferenceHome, Supplier<String> environmentHome,
            Supplier<String> runtimeHome) {
        this(preferenceHome, environmentHome, runtimeHome, path -> VersionStatus.SUPPORTED);
    }

    public JdkLocator(Supplier<String> preferenceHome, Supplier<String> environmentHome,
            Supplier<String> runtimeHome, Function<Path, VersionStatus> versionCheck) {
        this.preferenceHome = preferenceHome;
        this.environmentHome = environmentHome;
        this.runtimeHome = runtimeHome;
        this.versionCheck = versionCheck;
    }

    public static JdkLocator system(Supplier<String> preferenceHome) {
        CommandRunner versionRunner = CommandRunner.isolatedProcessBuilder();
        return new JdkLocator(
                preferenceHome,
                () -> System.getenv("JAVA_HOME"), //$NON-NLS-1$
                () -> System.getProperty("java.home"), //$NON-NLS-1$
                path -> VERSION_CACHE.computeIfAbsent(path, candidate -> checkVersion(versionRunner, candidate)));
    }

    public Location locate() {
        List<Candidate> candidates = List.of(
                new Candidate("preference", supplied(preferenceHome)), //$NON-NLS-1$
                new Candidate("env:JAVA_HOME", supplied(environmentHome)), //$NON-NLS-1$
                new Candidate("runtime:java.home", supplied(runtimeHome))); //$NON-NLS-1$
        List<String> checked = new ArrayList<>();
        String tooOldSource = null;
        for (Candidate candidate : candidates) {
            checked.add(candidate.source());
            Optional<Path> javac = javacIn(candidate.home());
            if (javac.isPresent()) {
                VersionStatus status = versionCheck.apply(javac.get());
                if (status == VersionStatus.SUPPORTED) {
                    return new Location(javac.get(), candidate.source(), String.join(", ", checked), ""); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (status == VersionStatus.TOO_OLD && tooOldSource == null) {
                    tooOldSource = candidate.source();
                }
            }
        }
        if (tooOldSource != null) {
            return new Location(null, tooOldSource, String.join(", ", checked), "javac_too_old"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new Location(null, "none", String.join(", ", checked), "javac_not_available"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static VersionStatus checkVersion(CommandRunner runner, Path javac) {
        CommandResult result = runner.run(List.of(
                javac.toString(),
                "-J-Duser.language=en", //$NON-NLS-1$
                "-J-Duser.country=US", //$NON-NLS-1$
                "-version"), Duration.ofSeconds(5)); //$NON-NLS-1$
        if (result.timedOut() || result.exitCode() != 0) {
            return VersionStatus.UNAVAILABLE;
        }
        String output = (result.stderr() + " " + result.stdout()).trim(); //$NON-NLS-1$
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:javac\\s+)?(\\d+)(?:\\.|$)") //$NON-NLS-1$
                .matcher(output);
        if (!matcher.find()) {
            return VersionStatus.UNAVAILABLE;
        }
        return Integer.parseInt(matcher.group(1)) >= 17
                ? VersionStatus.SUPPORTED : VersionStatus.TOO_OLD;
    }

    private static String supplied(Supplier<String> supplier) {
        try {
            return supplier == null ? null : supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Optional<Path> javacIn(String home) {
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        try {
            String executable = isWindows() ? "javac.exe" : "javac"; //$NON-NLS-1$ //$NON-NLS-2$
            Path path = Path.of(home).toAbsolutePath().normalize().resolve("bin").resolve(executable); //$NON-NLS-1$
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private record Candidate(String source, String home) {
    }

    public enum VersionStatus {
        SUPPORTED,
        TOO_OLD,
        UNAVAILABLE
    }

    public record Location(Path javac, String source, String checkedSources, String errorCode) {
        public boolean available() {
            return javac != null;
        }
    }
}
