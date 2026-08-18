/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.util.Objects;

/** Provider/model/mode identity used to detect potentially surprising resumes. */
public record SessionContext(String mode, String provider, String model, String endpointFingerprint) {
    public SessionContext {
        Objects.requireNonNull(mode, "mode"); //$NON-NLS-1$
        Objects.requireNonNull(provider, "provider"); //$NON-NLS-1$
        Objects.requireNonNull(model, "model"); //$NON-NLS-1$
        Objects.requireNonNull(endpointFingerprint, "endpointFingerprint"); //$NON-NLS-1$
        if (!endpointFingerprint.matches("[0-9a-f]{64}")) { //$NON-NLS-1$
            throw new IllegalArgumentException("invalid endpoint fingerprint"); //$NON-NLS-1$
        }
    }

    public static SessionContext fromEndpoint(
            String mode, String provider, String model, String endpoint, String instanceId) {
        return new SessionContext(mode, provider, model, SessionStore.endpointFingerprint(endpoint, instanceId));
    }
}
