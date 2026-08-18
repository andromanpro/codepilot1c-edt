/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/**
 * Classifies the side-effect profile of a task's execution.
 *
 * <p>Mutation kinds ({@link #FILE_MUTATION}, {@link #EDT_MUTATION}, {@link #GIT_MUTATION})
 * require serialized execution: a wave containing a mutation task must contain
 * <em>only</em> that one task. {@link #READ_ONLY} tasks carry no side effects and
 * may be parallelized within the same wave.</p>
 *
 * @see GsdGuard
 */
public enum GsdExecutionKind {

    /** No side effects; safe to parallelize with other read-only tasks. */
    READ_ONLY,

    /** Modifies files on disk (create, edit, delete). */
    FILE_MUTATION,

    /** Modifies the Eclipse EDT workspace (metadata, forms, configuration). */
    EDT_MUTATION,

    /** Modifies Git state (commit, branch, merge, push). */
    GIT_MUTATION;

    /**
     * Returns whether this kind represents a mutating operation that requires
     * serialized execution within its wave.
     *
     * @return {@code true} for any non-{@link #READ_ONLY} kind
     */
    public boolean isMutation() {
        return this != READ_ONLY;
    }

    /**
     * Parses an execution kind by name, case-insensitive; returns {@code null} if unknown.
     *
     * @param name the execution kind name
     * @return the kind, or {@code null} if not recognized
     */
    public static GsdExecutionKind fromName(String name) {
        if (name == null) {
            return null;
        }
        for (GsdExecutionKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return null;
    }
}
