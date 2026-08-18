/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.io.IOException;

/** Deterministic failure for metadata written by an unsupported schema version. */
public final class UnsupportedSessionSchemaException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int schemaVersion;

    public UnsupportedSessionSchemaException(int schemaVersion) {
        super("Unsupported session schema version: " + schemaVersion); //$NON-NLS-1$
        this.schemaVersion = schemaVersion;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
