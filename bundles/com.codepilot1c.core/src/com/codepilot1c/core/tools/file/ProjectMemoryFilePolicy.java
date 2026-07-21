/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import java.util.Locale;

/**
 * Centralizes the project memory file name so file tools handle model typos
 * consistently without creating sibling files with near-miss names.
 */
final class ProjectMemoryFilePolicy {

    static final String CANONICAL_FILE_NAME = "Code.md"; //$NON-NLS-1$

    private static final String CANONICAL_FILE_NAME_LOWER = "code.md"; //$NON-NLS-1$
    private static final String MODEL_OMISSION_ALIAS_LOWER = "cd.md"; //$NON-NLS-1$

    private ProjectMemoryFilePolicy() {
    }

    static String canonicalizeBarePath(String path) {
        if (!isKnownBareMemoryPath(path)) {
            return path;
        }
        return CANONICAL_FILE_NAME;
    }

    static boolean isCanonicalFileName(String fileName) {
        return fileName != null
                && CANONICAL_FILE_NAME_LOWER.equals(fileName.toLowerCase(Locale.ROOT));
    }

    static boolean isKnownBareMemoryPath(String path) {
        String candidate = bareCandidate(path);
        if (candidate == null) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        return CANONICAL_FILE_NAME_LOWER.equals(lower)
                || MODEL_OMISSION_ALIAS_LOWER.equals(lower);
    }

    static boolean isBarePath(String path) {
        return bareCandidate(path) != null;
    }

    private static String bareCandidate(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.trim().replace('\\', '/');
        if (normalized.startsWith("/") && !normalized.startsWith("//")) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("/")) { //$NON-NLS-1$
            return null;
        }
        return normalized;
    }
}
