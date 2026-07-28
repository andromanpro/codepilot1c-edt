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
 * A wave: a ordered batch of tasks executed together toward a sub-goal.
 *
 * @param id      stable wave identifier (non-blank, unique within state)
 * @param name    short human-readable name (non-blank)
 * @param goal    what this wave achieves (may be blank)
 * @param taskIds ordered task ids in this wave (never {@code null})
 */
public record GsdWave(
        String id,
        String name,
        String goal,
        List<String> taskIds) {

    /**
     * Canonical record constructor; defensive-copies lists and enforces non-null.
     */
    public GsdWave {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("wave id must be non-blank"); //$NON-NLS-1$
        }
        if (name == null) {
            name = ""; //$NON-NLS-1$
        }
        if (goal == null) {
            goal = ""; //$NON-NLS-1$
        }
        taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
    }
}