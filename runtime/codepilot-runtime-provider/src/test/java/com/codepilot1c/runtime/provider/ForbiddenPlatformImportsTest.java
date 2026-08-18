/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Locks the standalone provider boundary away from platform and core APIs. */
public class ForbiddenPlatformImportsTest {

    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "org.eclipse.", //$NON-NLS-1$
            "org.osgi.", //$NON-NLS-1$
            "com._1c.", //$NON-NLS-1$
            "com.e1c.", //$NON-NLS-1$
            "com.codepilot1c.core."); //$NON-NLS-1$

    @Test
    public void productionSourcesDoNotImportPlatformOrCoreApis() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("runtime.module.basedir")) //$NON-NLS-1$
                .resolve("src/main/java"); //$NON-NLS-1$
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) { //$NON-NLS-1$
                inspect(sourceRoot, source, violations);
            }
        }

        assertTrue("Forbidden provider imports:\n" + String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void inspect(Path sourceRoot, Path source, List<String> violations) throws IOException {
        int lineNumber = 0;
        for (String line : Files.readAllLines(source)) {
            lineNumber++;
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) { //$NON-NLS-1$
                continue;
            }
            String imported = trimmed.substring("import ".length()).replace(";", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (String prefix : FORBIDDEN_PREFIXES) {
                if (imported.startsWith(prefix)) {
                    violations.add(sourceRoot.relativize(source) + ":" + lineNumber + ": " + imported); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
                }
            }
        }
    }
}
