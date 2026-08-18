/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.Objects;

/**
 * Pointer to the active session and workstream for GSD coordination.
 *
 * @param sessionId    active session id (may be blank if none)
 * @param workstreamId active workstream id (may be blank if none)
 */
public record GsdSessionPointer(
        String sessionId,
        String workstreamId) {

    /**
     * Canonical record constructor; enforces non-null fields.
     */
    public GsdSessionPointer {
        sessionId = sessionId == null ? "" : sessionId; //$NON-NLS-1$
        workstreamId = workstreamId == null ? "" : workstreamId; //$NON-NLS-1$
    }

    /**
     * Empty pointer (no active session/workstream).
     *
     * @return the empty pointer
     */
    public static GsdSessionPointer empty() {
        return new GsdSessionPointer("", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether a session is currently active.
     *
     * @return {@code true} if a non-blank session id is set
     */
    public boolean isActive() {
        return !sessionId.isBlank();
    }

    /**
     * Factory honoring null-safety.
     *
     * @param sessionId    the session id
     * @param workstreamId the workstream id
     * @return the pointer
     */
    public static GsdSessionPointer of(String sessionId, String workstreamId) {
        return new GsdSessionPointer(
                Objects.requireNonNullElse(sessionId, ""), //$NON-NLS-1$
                Objects.requireNonNullElse(workstreamId, "")); //$NON-NLS-1$
    }
}