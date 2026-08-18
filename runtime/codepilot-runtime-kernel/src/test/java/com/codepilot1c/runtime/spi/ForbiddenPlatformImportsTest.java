/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.spi;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Prevents accidental platform API coupling in production kernel sources. */
public class ForbiddenPlatformImportsTest {

    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "org.eclipse.", //$NON-NLS-1$
            "org.eclipse.swt.", //$NON-NLS-1$
            "org.osgi.", //$NON-NLS-1$
            "com._1c.", //$NON-NLS-1$
            "com.e1c."); //$NON-NLS-1$

    @Test
    public void productionSourcesDoNotImportPlatformApis() throws IOException {
        Path moduleRoot = Path.of(System.getProperty("runtime.kernel.basedir")); //$NON-NLS-1$
        Path sourceRoot = moduleRoot.resolve("src/main/java"); //$NON-NLS-1$
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) { //$NON-NLS-1$
                inspect(sourceRoot, source, violations);
            }
        }

        assertTrue("Forbidden platform imports:\n" + String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void inspect(Path sourceRoot, Path source, List<String> violations) throws IOException {
        int lineNumber = 0;
        for (String line : Files.readAllLines(source)) {
            lineNumber++;
            String importedType = importedType(line);
            if (importedType == null) {
                continue;
            }
            for (String prefix : FORBIDDEN_PREFIXES) {
                if (importedType.startsWith(prefix)) {
                    violations.add(sourceRoot.relativize(source) + ":" + lineNumber + ": " + importedType); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
                }
            }
        }
    }

    private static String importedType(String line) {
        String candidate = line.trim();
        if (!candidate.startsWith("import ")) { //$NON-NLS-1$
            return null;
        }
        candidate = candidate.substring("import ".length()).trim(); //$NON-NLS-1$
        if (candidate.startsWith("static ")) { //$NON-NLS-1$
            candidate = candidate.substring("static ".length()).trim(); //$NON-NLS-1$
        }
        return candidate.endsWith(";") ? candidate.substring(0, candidate.length() - 1) : candidate; //$NON-NLS-1$
    }
}
