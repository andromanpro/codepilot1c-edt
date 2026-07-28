/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/**
 * Provenance of a piece of evidence. {@link #INFERRED} evidence is the weakest form and
 * may not, by itself, justify closing a task or phase (see {@link GsdGuard}).
 */
public enum GsdProvenance {

    /** Observed directly by the agent in the running system (logs, state, output). */
    OBSERVED,

    /** Verified by an executable test that passed. */
    TESTED,

    /** Explicitly accepted by the user (approval, confirmation). */
    USER_ACCEPTED,

    /** Inferred without direct observation or test; cannot close work on its own. */
    INFERRED;

    /**
     * Parses provenance by name, case-insensitive; returns {@code null} if unknown.
     *
     * @param name the provenance name
     * @return the provenance, or {@code null} if not recognized
     */
    public static GsdProvenance fromName(String name) {
        if (name == null) {
            return null;
        }
        for (GsdProvenance provenance : values()) {
            if (provenance.name().equalsIgnoreCase(name)) {
                return provenance;
            }
        }
        return null;
    }

    /**
     * Returns whether this provenance is strong enough to close work.
     *
     * @return {@code true} for any non-{@link #INFERRED} provenance
     */
    public boolean isClosable() {
        return this != INFERRED;
    }
}