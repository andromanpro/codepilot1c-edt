/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies the filesystem context captured for a runtime operation.
 *
 * <p>This narrow port does not discover IDE projects, refresh resources,
 * inspect editors, or mutate workspace state. Those behaviors require host
 * policy and remain outside the kernel until a platform-neutral use case is
 * reviewed.</p>
 */
@FunctionalInterface
public interface WorkspaceContextPort {

    /**
     * Captures the current immutable workspace selection.
     *
     * @return current workspace snapshot
     */
    Context snapshot();

    /**
     * Filesystem context for one runtime operation.
     *
     * @param workspaceRoot absolute workspace root
     * @param activeProject optional project selected by the host
     */
    record Context(Path workspaceRoot, Optional<Project> activeProject) {

        /**
         * Validates and normalizes paths without touching the filesystem.
         *
         * @param workspaceRoot absolute workspace root
         * @param activeProject optional host-selected project
         */
        public Context {
            workspaceRoot = absoluteNormalized(workspaceRoot, "workspaceRoot"); //$NON-NLS-1$
            Objects.requireNonNull(activeProject, "activeProject"); //$NON-NLS-1$
        }
    }

    /**
     * Selected project identity without an IDE project handle.
     *
     * @param name host-visible project name
     * @param root absolute project root; it may be outside the workspace root
     *             for hosts supporting linked projects
     */
    record Project(String name, Path root) {

        /**
         * Validates project identity and normalizes its root.
         *
         * @param name host-visible project name
         * @param root absolute project root
         */
        public Project {
            Objects.requireNonNull(name, "name"); //$NON-NLS-1$
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank"); //$NON-NLS-1$
            }
            root = absoluteNormalized(root, "root"); //$NON-NLS-1$
        }
    }

    private static Path absoluteNormalized(Path path, String field) {
        Objects.requireNonNull(path, field);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be absolute"); //$NON-NLS-1$
        }
        return path.normalize();
    }
}
