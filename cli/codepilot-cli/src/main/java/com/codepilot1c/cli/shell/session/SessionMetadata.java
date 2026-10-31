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
        String mcpEndpointFingerprint,
        String providerEndpointFingerprint,
        long turns,
        long messageCount) {

    /** Source-compatible constructor for schema-v1 callers predating split provenance. */
    public SessionMetadata(int schemaVersion, UUID id, String title, Instant createdAt,
            Instant updatedAt, String mode, String provider, String model,
            String endpointFingerprint, long turns, long messageCount) {
        this(schemaVersion, id, title, createdAt, updatedAt, mode, provider, model,
                endpointFingerprint, endpointFingerprint, "", turns, messageCount); //$NON-NLS-1$
    }

    public SessionMetadata {
        Objects.requireNonNull(id, "id"); //$NON-NLS-1$
        Objects.requireNonNull(title, "title"); //$NON-NLS-1$
        Objects.requireNonNull(createdAt, "createdAt"); //$NON-NLS-1$
        Objects.requireNonNull(updatedAt, "updatedAt"); //$NON-NLS-1$
        Objects.requireNonNull(mode, "mode"); //$NON-NLS-1$
        Objects.requireNonNull(provider, "provider"); //$NON-NLS-1$
        Objects.requireNonNull(model, "model"); //$NON-NLS-1$
        Objects.requireNonNull(endpointFingerprint, "endpointFingerprint"); //$NON-NLS-1$
        Objects.requireNonNull(mcpEndpointFingerprint, "mcpEndpointFingerprint"); //$NON-NLS-1$
        Objects.requireNonNull(providerEndpointFingerprint, "providerEndpointFingerprint"); //$NON-NLS-1$
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
        validateOptional(mcpEndpointFingerprint);
        validateOptional(providerEndpointFingerprint);
        if (turns < 0 || messageCount < 0 || turns > messageCount) {
            throw new IllegalArgumentException("invalid session counts"); //$NON-NLS-1$
        }
    }

    public SessionContext context() {
        if (mcpEndpointFingerprint.isEmpty() && !"connected".equalsIgnoreCase(mode)) { //$NON-NLS-1$
            // Older standalone files used endpointFingerprint for the provider
            // endpoint. Its MCP provenance is unknowable, so never report a
            // false MCP mismatch when resuming it.
            return new SessionContext(mode, provider, model, "", endpointFingerprint); //$NON-NLS-1$
        }
        if (mcpEndpointFingerprint.isEmpty()) {
            return new SessionContext(mode, provider, model, endpointFingerprint, ""); //$NON-NLS-1$
        }
        return new SessionContext(mode, provider, model,
                mcpEndpointFingerprint, providerEndpointFingerprint);
    }

    private static void validateOptional(String fingerprint) {
        if (!fingerprint.isEmpty() && !fingerprint.matches("[0-9a-f]{64}")) { //$NON-NLS-1$
            throw new IllegalArgumentException("invalid endpoint fingerprint"); //$NON-NLS-1$
        }
    }
}
