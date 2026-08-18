/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

/** Optional host-facing hints about a tool's behavior and approval needs. */
public record ToolAnnotations(
        String title, boolean destructive, boolean readOnly, boolean requiresConfirmation) {
    public ToolAnnotations {
        title = title == null ? "" : title; //$NON-NLS-1$
    }
}
