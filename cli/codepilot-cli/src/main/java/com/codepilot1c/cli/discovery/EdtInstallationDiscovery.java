/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.codepilot1c.cli.platform.HostSystem;
import com.codepilot1c.cli.platform.OperatingSystem;

/** Discovers EDT without invoking it or depending on Eclipse APIs. */
public final class EdtInstallationDiscovery {
    private final HostSystem host;

    public EdtInstallationDiscovery(HostSystem host) { this.host = host; }

    public List<EdtInstallation> discover() {
        OperatingSystem os = OperatingSystem.from(host.osName());
        List<Candidate> candidates = new ArrayList<>();
        add(candidates, host.systemProperty("edt.home"), "system-property");
        add(candidates, host.environment("EDT_HOME"), "environment");
        addStandardRoots(candidates, os);
        addPathEntries(candidates, os);

        Map<String, EdtInstallation> found = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            inspect(candidate.path(), candidate.source(), os, found);
        }
        return found.values().stream()
                .sorted(Comparator.comparing(EdtInstallation::home, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Validates one explicit Eclipse home without mutating process properties. */
    public Optional<EdtInstallation> validateHome(String home) {
        if (home == null || home.isBlank()) return Optional.empty();
        OperatingSystem os = OperatingSystem.from(host.osName());
        Map<String, EdtInstallation> found = new LinkedHashMap<>();
        detectHome(home.trim(), "command-line", os, found);
        detectApp(home.trim(), "command-line", os, found);
        return found.values().stream().findFirst();
    }

    private void addStandardRoots(List<Candidate> candidates, OperatingSystem os) {
        String home = host.userHome();
        switch (os) {
        case MACOS -> {
            add(candidates, "/Applications/1C/1CE/components", "standard");
            add(candidates, "/Applications", "standard");
            add(candidates, join(os, home, "Applications", "1C", "1CE", "components"), "standard");
        }
        case LINUX -> {
            add(candidates, "/opt/1C/1CE/components", "standard");
            add(candidates, "/opt/1C", "standard");
            add(candidates, "/usr/local/1C/1CE/components", "standard");
            add(candidates, join(os, home, ".local", "share", "1C", "1CE", "components"), "standard");
        }
        case WINDOWS -> {
            add(candidates, join(os, host.environment("ProgramFiles"), "1C", "1CE", "components"), "standard");
            add(candidates, join(os, host.environment("ProgramFiles(x86)"), "1C", "1CE", "components"), "standard");
            add(candidates, join(os, host.environment("LOCALAPPDATA"), "1C", "1CE", "components"), "standard");
        }
        case OTHER -> { }
        }
    }

    private void addPathEntries(List<Candidate> candidates, OperatingSystem os) {
        String path = host.environment("PATH");
        if (path == null || path.isBlank()) return;
        String delimiter = os == OperatingSystem.WINDOWS ? ";" : ":";
        for (String entry : path.split(java.util.regex.Pattern.quote(delimiter))) {
            add(candidates, entry, "path");
        }
    }

    private void inspect(String path, String source, OperatingSystem os, Map<String, EdtInstallation> found) {
        detectHome(path, source, os, found);
        detectApp(path, source, os, found);
        if (!host.isDirectory(path)) return;
        for (String child : host.children(path)) {
            detectHome(child, source, os, found);
            detectApp(child, source, os, found);
            if (host.isDirectory(child) && looksLikeEdt(child)) {
                for (String grandchild : host.children(child)) {
                    detectHome(grandchild, source, os, found);
                    detectApp(grandchild, source, os, found);
                }
            }
        }
    }

    private void detectApp(String app, String source, OperatingSystem os, Map<String, EdtInstallation> found) {
        if (os != OperatingSystem.MACOS || !app.toLowerCase(Locale.ROOT).endsWith(".app")) return;
        detectHome(join(os, app, "Contents", "Eclipse"), source, os, found);
    }

    private void detectHome(String home, String source, OperatingSystem os, Map<String, EdtInstallation> found) {
        if (home == null || home.isBlank() || !host.isDirectory(home)) return;
        List<String> launcherNames = os == OperatingSystem.WINDOWS
                ? List.of("1cedtcli.exe", "1cedt.exe") : List.of("1cedtcli", "1cedt");
        for (String launcherName : launcherNames) {
            String launcher = join(os, home, launcherName);
            if (host.isRegularFile(launcher)) {
                found.putIfAbsent(normalizedKey(home, os), new EdtInstallation(home, launcher, source));
                return;
            }
        }
    }

    private static boolean looksLikeEdt(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("edt") || lower.contains("1ce");
    }

    private static String normalizedKey(String path, OperatingSystem os) {
        String value = path.replace('\\', '/');
        while (value.endsWith("/") && value.length() > 1) value = value.substring(0, value.length() - 1);
        return os == OperatingSystem.WINDOWS ? value.toLowerCase(Locale.ROOT) : value;
    }

    static String join(OperatingSystem os, String first, String... rest) {
        if (first == null || first.isBlank()) return null;
        String result = trimEnd(first, os.separator());
        for (String part : rest) {
            if (part == null || part.isBlank()) return null;
            result += os.separator() + trimBoth(part, os.separator());
        }
        return result;
    }

    private static String trimEnd(String value, String separator) {
        while (value.endsWith(separator) && value.length() > 1) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String trimBoth(String value, String separator) {
        while (value.startsWith(separator)) value = value.substring(1);
        return trimEnd(value, separator);
    }

    private static void add(List<Candidate> candidates, String path, String source) {
        if (path != null && !path.isBlank()) candidates.add(new Candidate(path.trim(), source));
    }

    private record Candidate(String path, String source) { }
}
