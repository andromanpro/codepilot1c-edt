/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable schema-v1 summary stored in {@code <uuid>.meta.json}.
 * {@code turns} is the number of valid persisted {@code USER} text messages;
 * {@code messageCount} covers every valid provider-neutral transcript record.
 */
public record SessionMetadata(
        int schemaVersion,
        UUID id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        String mode,
        String provider,
        String model,
        String endpointFingerprint,
        long turns,
        long messageCount) {

    public SessionMetadata {
        Objects.requireNonNull(id, "id"); //$NON-NLS-1$
        Objects.requireNonNull(title, "title"); //$NON-NLS-1$
        Objects.requireNonNull(createdAt, "createdAt"); //$NON-NLS-1$
        Objects.requireNonNull(updatedAt, "updatedAt"); //$NON-NLS-1$
        Objects.requireNonNull(mode, "mode"); //$NON-NLS-1$
        Objects.requireNonNull(provider, "provider"); //$NON-NLS-1$
        Objects.requireNonNull(model, "model"); //$NON-NLS-1$
        Objects.requireNonNull(endpointFingerprint, "endpointFingerprint"); //$NON-NLS-1$
        if (schemaVersion != SessionStore.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported session schema"); //$NON-NLS-1$
        }
        if (title.codePointCount(0, title.length()) > SessionStore.MAX_TITLE_CODE_POINTS) {
            throw new IllegalArgumentException("session title is too long"); //$NON-NLS-1$
        }
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt"); //$NON-NLS-1$
        if (!endpointFingerprint.matches("[0-9a-f]{64}")) { //$NON-NLS-1$
            throw new IllegalArgumentException("invalid endpoint fingerprint"); //$NON-NLS-1$
        }
        if (turns < 0 || messageCount < 0 || turns > messageCount) {
            throw new IllegalArgumentException("invalid session counts"); //$NON-NLS-1$
        }
    }

    public SessionContext context() {
        return new SessionContext(mode, provider, model, endpointFingerprint);
    }
}
