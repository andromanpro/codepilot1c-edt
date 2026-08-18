/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.List;

/**
 * A unit of work toward the goal.
 *
 * @param id             stable task identifier (non-blank, unique within state)
 * @param title          short human-readable title (non-blank)
 * @param status         lifecycle status (never {@code null})
 * @param waveId         optional wave id this task belongs to (may be {@code null}/blank)
 * @param dependsOn      ids of tasks that must complete first (never {@code null})
 * @param evidenceIds    ids of evidence backing this task (never {@code null})
 * @param executionKind  side-effect profile (never {@code null}; defaults to {@link GsdExecutionKind#READ_ONLY})
 */
public record GsdTask(
        String id,
        String title,
        GsdTaskStatus status,
        String waveId,
        List<String> dependsOn,
        List<String> evidenceIds,
        GsdExecutionKind executionKind) {

    /**
     * Canonical record constructor; defensive-copies lists and enforces non-null.
     */
    public GsdTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("task id must be non-blank"); //$NON-NLS-1$
        }
        if (title == null) {
            title = ""; //$NON-NLS-1$
        }
        if (status == null) {
            status = GsdTaskStatus.PENDING;
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (executionKind == null) {
            executionKind = GsdExecutionKind.READ_ONLY;
        }
    }

    /**
     * Backward-compatible constructor (6-arg) for source compatibility.
     * Defaults {@code executionKind} to {@link GsdExecutionKind#READ_ONLY}.
     */
    public GsdTask(String id, String title, GsdTaskStatus status, String waveId,
                   List<String> dependsOn, List<String> evidenceIds) {
        this(id, title, status, waveId, dependsOn, evidenceIds, GsdExecutionKind.READ_ONLY);
    }

    /**
     * Convenience factory for a pending task with no dependencies or evidence.
     *
     * @param id    the id
     * @param title the title
     * @return the task
     */
    public static GsdTask pending(String id, String title) {
        return new GsdTask(id, title, GsdTaskStatus.PENDING, null, List.of(), List.of(), GsdExecutionKind.READ_ONLY);
    }
}