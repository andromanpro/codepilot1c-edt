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
 * A single decision captured during GSD, with rationale.
 *
 * @param id           stable decision identifier (non-blank, unique within state)
 * @param summary      short human-readable summary (non-blank)
 * @param rationale    rationale / why (non-blank)
 * @param alternatives alternatives considered (never {@code null})
 */
public record GsdDecision(
        String id,
        String summary,
        String rationale,
        List<String> alternatives) {

    /**
     * Canonical record constructor; defensive-copies lists and enforces non-null.
     */
    public GsdDecision {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("decision id must be non-blank"); //$NON-NLS-1$
        }
        if (summary == null) {
            summary = ""; //$NON-NLS-1$
        }
        if (rationale == null) {
            rationale = ""; //$NON-NLS-1$
        }
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    /**
     * Convenience factory.
     *
     * @param id         the id
     * @param summary    the summary
     * @param rationale  the rationale
     * @return the decision
     */
    public static GsdDecision of(String id, String summary, String rationale) {
        return new GsdDecision(id, summary, rationale, List.of());
    }
}