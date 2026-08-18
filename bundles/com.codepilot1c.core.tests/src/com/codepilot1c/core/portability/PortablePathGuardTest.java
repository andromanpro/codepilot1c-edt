package com.codepilot1c.core.portability;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Prevents test fixtures and executable build configuration from acquiring a
 * developer-machine home directory. Project-relative paths and synthetic paths
 * such as {@code /project} remain valid.
 */
public class PortablePathGuardTest {

    private static final Pattern UNIX_DEVELOPER_HOME = Pattern.compile(
            "(?<![A-Za-z0-9_])" + "/" + "(Users|home)" + "/" + "[^\\s\\\"'<>]+"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final Pattern WINDOWS_DEVELOPER_HOME = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])[A-Z]:[\\\\/]Users[\\\\/][^\\s\\\"'<>]+"); //$NON-NLS-1$

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".sse", ".xml", ".pom", ".properties", ".sh", ".cmd", ".bat", ".yml", ".yaml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
    private static final Set<String> GENERATED_ARTIFACT_FILENAMES = Set.of(
            ".tycho-consumer-pom.xml"); //$NON-NLS-1$

    @Test
    public void excludesOnlyTychoGeneratedConsumerPom() {
        assertTrue(isGeneratedArtifact(Path.of(".tycho-consumer-pom.xml"))); //$NON-NLS-1$
        assertTrue(isGeneratedArtifact(Path.of("bundles", "com.codepilot1c.core", ".tycho-consumer-pom.xml"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(isGeneratedArtifact(Path.of("pom.xml"))); //$NON-NLS-1$
        assertFalse(isGeneratedArtifact(Path.of(".tycho-consumer-pom.xml.bak"))); //$NON-NLS-1$
    }

    @Test
    public void testAndBuildInputsDoNotContainDeveloperHomePaths() throws IOException {
        Path repository = findRepositoryRoot();
        List<String> violations = new ArrayList<>();

        for (Path root : scanRoots(repository)) {
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                inspect(root, repository, violations);
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !isGeneratedArtifact(path))
                        .filter(path -> TEXT_EXTENSIONS.contains(extension(path)))
                        .filter(path -> !hasPathSegment(path, "target")) //$NON-NLS-1$
                        .forEach(path -> inspect(path, repository, violations));
            }
        }

        assertTrue("Developer-home paths found in test/build inputs: " + violations, violations.isEmpty()); //$NON-NLS-1$
    }

    private List<Path> scanRoots(Path repository) {
        return List.of(
                repository.resolve("bundles"), //$NON-NLS-1$
                repository.resolve("features"), //$NON-NLS-1$
                repository.resolve("repositories"), //$NON-NLS-1$
                repository.resolve("runtime"), //$NON-NLS-1$
                repository.resolve("targets"), //$NON-NLS-1$
                repository.resolve("tools"), //$NON-NLS-1$
                repository.resolve(".github"), //$NON-NLS-1$
                repository.resolve("packaging"), //$NON-NLS-1$
                repository.resolve("pom.xml"), //$NON-NLS-1$
                repository.resolve("bom")); //$NON-NLS-1$
    }

    private void inspect(Path file, Path repository, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (UNIX_DEVELOPER_HOME.matcher(line).find()
                        || WINDOWS_DEVELOPER_HOME.matcher(line).find()) {
                    violations.add(repository.relativize(file) + ":" + (index + 1)); //$NON-NLS-1$
                }
            }
        } catch (IOException exception) {
            throw new AssertionError("Cannot inspect portability guard input " + file, exception); //$NON-NLS-1$
        }
    }

    private String extension(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot); //$NON-NLS-1$
    }

    private boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (segment.equalsIgnoreCase(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isGeneratedArtifact(Path path) {
        return GENERATED_ARTIFACT_FILENAMES.contains(path.getFileName().toString());
    }

    private Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.exists(current.resolve(".git")) && Files.exists(current.resolve("pom.xml"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Cannot locate repository root from user.dir=" //$NON-NLS-1$
                + System.getProperty("user.dir")); //$NON-NLS-1$
    }
}
