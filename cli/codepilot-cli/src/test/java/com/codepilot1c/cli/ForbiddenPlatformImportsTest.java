/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class ForbiddenPlatformImportsTest {
    @Test public void productionSourcesStayPlatformNeutral() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("cli.module.basedir"), "src/main/java");
        List<String> violations = new ArrayList<>();
        try (var sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                int line = 0;
                for (String text : Files.readAllLines(source)) {
                    line++;
                    String trimmed = text.trim();
                    if (trimmed.startsWith("import org.eclipse.") || trimmed.startsWith("import org.osgi.")
                            || trimmed.startsWith("import com._1c.")) {
                        violations.add(sourceRoot.relativize(source) + ":" + line + ": " + trimmed);
                    }
                }
            }
        }
        assertTrue("Forbidden platform imports:\n" + String.join("\n", violations), violations.isEmpty());
    }
}
