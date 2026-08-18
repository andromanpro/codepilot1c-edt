package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

public class NoDirectPlatformServiceInstantiationGuardTest {

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\bnew\\s+FormItemInformation" + "Service\\s*\\("), //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.compile("\\bnew\\s+FormIdentifier" + "Service\\s*\\("), //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.compile("\\bnew\\s+FormItemInformationEvent" + "Catalog\\s*\\(\\s*\\)"), //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.compile("\\bnew\\s+EventHandlerTarget" + "Resolver\\s*\\(\\s*\\)")); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void productionCodeNeverInstantiatesGuiceManagedEdtServicesDirectly() throws Exception {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        collectJava(files, root.resolve("bundles/com.codepilot1c.core/src")); //$NON-NLS-1$
        collectJava(files, root.resolve("bundles/com.codepilot1c.ui/src")); //$NON-NLS-1$

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (Pattern forbidden : FORBIDDEN) {
                if (forbidden.matcher(content).find()) {
                    violations.add(root.relativize(file) + " -> " + forbidden.pattern()); //$NON-NLS-1$
                }
            }
        }

        assertTrue(String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath(); //$NON-NLS-1$
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isDirectory(candidate.resolve("bundles/com.codepilot1c.core"))) { //$NON-NLS-1$
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root not found"); //$NON-NLS-1$
    }

    private static void collectJava(List<Path> files, Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")) //$NON-NLS-1$
                    .forEach(files::add);
        }
    }
}
