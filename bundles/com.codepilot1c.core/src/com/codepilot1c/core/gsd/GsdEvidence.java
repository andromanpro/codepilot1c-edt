/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.time.Instant;
import java.util.List;

/**
 * A piece of evidence backing a task or decision.
 *
 * @param id          stable evidence identifier (non-blank, unique within state)
 * @param description what the evidence shows (non-blank)
 * @param provenance  how the evidence was obtained (never {@code null})
 * @param taskIds     tasks this evidence supports (never {@code null})
 * @param createdAt   when the evidence was recorded (never {@code null})
 */
public record GsdEvidence(
        String id,
        String description,
        GsdProvenance provenance,
        List<String> taskIds,
        Instant createdAt) {

    /**
     * Canonical record constructor; defensive-copies lists and enforces non-null.
     */
    public GsdEvidence {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("evidence id must be non-blank"); //$NON-NLS-1$
        }
        if (description == null) {
            description = ""; //$NON-NLS-1$
        }
        if (provenance == null) {
            provenance = GsdProvenance.INFERRED;
        }
        taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }
}