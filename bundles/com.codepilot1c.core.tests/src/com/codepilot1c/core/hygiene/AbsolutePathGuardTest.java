package com.codepilot1c.core.hygiene;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;

public class AbsolutePathGuardTest {
    private static final List<String> FORBIDDEN_HOME_PATHS = List.of(
            "\"/Us" + "ers/", //$NON-NLS-1$ //$NON-NLS-2$
            "\"/ho" + "me/", //$NON-NLS-1$ //$NON-NLS-2$
            "\"C:\\\\" + "Users\\\\"); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void javaSourcesDoNotContainAbsoluteHomePaths() throws Exception {
        Path root = findRepositoryRoot();
        Assume.assumeTrue("repository root not found; absolute-path guard requires a source checkout", root != null); //$NON-NLS-1$

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root.resolve("bundles"))) { //$NON-NLS-1$
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")) //$NON-NLS-1$
                    .sorted()
                    .forEach(path -> inspectFile(root, path, violations));
        }

        assertTrue(String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$
    }

    private static void inspectFile(Path root, Path file, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                for (String forbidden : FORBIDDEN_HOME_PATHS) {
                    if (line.contains(forbidden)) {
                        violations.add(root.relativize(file) + ":" + (index + 1) //$NON-NLS-1$
                                + " contains absolute home path " + forbidden); //$NON-NLS-1$
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect " + file, e); //$NON-NLS-1$
        }
    }

    private static Path findRepositoryRoot() {
        String multiModuleDirectory = System.getProperty("maven.multiModuleProjectDirectory"); //$NON-NLS-1$
        Path fromMaven = multiModuleDirectory == null ? null : findRepositoryRoot(Path.of(multiModuleDirectory));
        if (fromMaven != null) {
            return fromMaven;
        }
        return findRepositoryRoot(Path.of(System.getProperty("user.dir"))); //$NON-NLS-1$
    }

    private static Path findRepositoryRoot(Path start) {
        Path candidate = start.toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isDirectory(candidate.resolve("bundles/com.codepilot1c.core"))) { //$NON-NLS-1$
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
