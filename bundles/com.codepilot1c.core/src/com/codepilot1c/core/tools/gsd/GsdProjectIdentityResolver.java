/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import com.codepilot1c.core.tools.ActiveProjectSupport;

/** Resolves strict GSD path identities against the actual open EDT workspace projects. */
final class GsdProjectIdentityResolver {

    private static final Path WORKSPACE_ALIAS_ROOT = Path.of("/workspace"); //$NON-NLS-1$

    private GsdProjectIdentityResolver() {
    }

    /** Immutable, provider-neutral workspace project identity used by the pure resolver. */
    record ProjectIdentity(String name, Path location) {

        ProjectIdentity {
            Objects.requireNonNull(name, "name"); //$NON-NLS-1$
            Objects.requireNonNull(location, "location"); //$NON-NLS-1$
            if (name.isBlank() || !isSingleSafeName(name)) {
                throw new IllegalArgumentException("workspace project name is invalid"); //$NON-NLS-1$
            }
            if (!location.isAbsolute()) {
                throw new IllegalArgumentException("workspace project location must be absolute"); //$NON-NLS-1$
            }
            location = location.normalize();
        }
    }

    /** Resolves using the current open Eclipse/EDT workspace project identities. */
    static String resolve(String requested, String captured) {
        return resolve(requested, captured, openWorkspaceProjects());
    }

    /**
     * Resolves a requested path without trusting a basename globally.
     *
     * <p>The exact captured identity remains valid for headless callers. A physical project
     * location or {@code /workspace/<project-name>} alias is accepted only when the captured
     * identity selects exactly one supplied workspace project. The legacy malformed capture
     * {@code <physical-location>/<project-name>} is recognized only as an explicit mapping to
     * that same project.</p>
     */
    static String resolve(String requested, String captured, List<ProjectIdentity> projects) {
        rejectRawTraversal(requested, "project_path traversal is not allowed"); //$NON-NLS-1$
        Path requestedPath = absolutePath(requested, "project_path is invalid"); //$NON-NLS-1$
        rejectRawTraversal(captured,
                "captured project_path traversal is not allowed"); //$NON-NLS-1$
        Path capturedPath = absolutePath(captured,
                "captured project_path identity is invalid"); //$NON-NLS-1$
        Path normalizedRequested = requestedPath.normalize();
        Path normalizedCaptured = capturedPath.normalize();

        Set<ProjectIdentity> matches = new LinkedHashSet<>();
        if (projects != null) {
            for (ProjectIdentity project : projects) {
                if (project != null && captures(project, normalizedCaptured)) {
                    matches.add(project);
                }
            }
        }
        if (matches.size() > 1) {
            throw new IdentityResolutionException(
                    "captured execution identity is ambiguous in the EDT workspace"); //$NON-NLS-1$
        }
        if (matches.size() == 1) {
            ProjectIdentity project = matches.iterator().next();
            if (samePhysicalPath(normalizedRequested, normalizedCaptured)
                    || samePhysicalPath(normalizedRequested, project.location())
                    || sameWorkspaceAlias(normalizedRequested, workspaceAlias(project.name()))) {
                return project.location().toString();
            }
            throw mismatch();
        }

        // Preserve strict exact-identity operation for headless/no-workspace execution.
        if (samePhysicalPath(normalizedRequested, normalizedCaptured)) {
            return normalizedCaptured.toString();
        }
        throw mismatch();
    }

    private static boolean captures(ProjectIdentity project, Path captured) {
        return samePhysicalPath(captured, project.location())
                || sameWorkspaceAlias(captured, workspaceAlias(project.name()))
                || samePhysicalPath(
                        captured, project.location().resolve(project.name()).normalize());
    }

    private static Path workspaceAlias(String projectName) {
        return WORKSPACE_ALIAS_ROOT.resolve(projectName).normalize();
    }

    private static boolean samePhysicalPath(Path first, Path second) {
        return first.normalize().equals(second.normalize());
    }

    private static boolean sameWorkspaceAlias(Path first, Path second) {
        return canonicalAliasKey(first).equals(canonicalAliasKey(second));
    }

    private static String canonicalAliasKey(Path path) {
        return Normalizer.normalize(path.normalize().toString(), Normalizer.Form.NFC);
    }

    private static Path absolutePath(String value, String invalidMessage) {
        if (value == null || value.isBlank()) {
            throw new IdentityResolutionException(invalidMessage);
        }
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                throw new IdentityResolutionException(
                        "GSD execution requires an absolute project_path identity"); //$NON-NLS-1$
            }
            return path;
        } catch (InvalidPathException e) {
            throw new IdentityResolutionException(invalidMessage);
        }
    }

    private static void rejectRawTraversal(String value, String message) {
        if (value == null) {
            return;
        }
        int segmentStart = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '/' || value.charAt(i) == '\\') {
                String segment = value.substring(segmentStart, i);
                if (".".equals(segment) || "..".equals(segment)) { //$NON-NLS-1$ //$NON-NLS-2$
                    throw new IdentityResolutionException(message);
                }
                segmentStart = i + 1;
            }
        }
    }

    private static boolean isSingleSafeName(String name) {
        try {
            Path path = Path.of(name);
            return !path.isAbsolute() && path.getNameCount() == 1
                    && !".".equals(name) && !"..".equals(name); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static List<ProjectIdentity> openWorkspaceProjects() {
        List<ProjectIdentity> result = new ArrayList<>();
        try {
            for (IProject project : ActiveProjectSupport.openProjects()) {
                IPath location = project.getLocation();
                if (location == null) {
                    continue;
                }
                try {
                    result.add(new ProjectIdentity(
                            project.getName(), Path.of(location.toOSString())));
                } catch (IllegalArgumentException e) {
                    // Ignore malformed workspace entries; they cannot authorize an alias.
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
        result.sort(Comparator.comparing(ProjectIdentity::name)
                .thenComparing(project -> project.location().toString()));
        return List.copyOf(result);
    }

    private static IdentityResolutionException mismatch() {
        return new IdentityResolutionException(
                "project_path does not match the captured execution identity"); //$NON-NLS-1$
    }

    static final class IdentityResolutionException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        IdentityResolutionException(String message) {
            super(message);
        }
    }
}
