/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/**
 * Status of a GSD task. Only {@link #DONE} is subject to the provenance guard.
 */
public enum GsdTaskStatus {

    /** Not started. */
    PENDING,

    /** Actively being worked on. */
    IN_PROGRESS,

    /** Blocked by an unresolved dependency or external factor. */
    BLOCKED,

    /** Completed; requires non-{@link GsdProvenance#INFERRED} evidence. */
    DONE;

    /**
     * Parses a status by name, case-insensitive; returns {@code null} if unknown.
     *
     * @param name the status name
     * @return the status, or {@code null} if not recognized
     */
    public static GsdTaskStatus fromName(String name) {
        if (name == null) {
            return null;
        }
        for (GsdTaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) {
                return status;
            }
        }
        return null;
    }
}