/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.util.Objects;

/** Provider/model/mode identity plus separately fingerprinted MCP/provider endpoints. */
public record SessionContext(String mode, String provider, String model,
        String mcpEndpointFingerprint, String providerEndpointFingerprint) {
    public SessionContext {
        Objects.requireNonNull(mode, "mode"); //$NON-NLS-1$
        Objects.requireNonNull(provider, "provider"); //$NON-NLS-1$
        Objects.requireNonNull(model, "model"); //$NON-NLS-1$
        Objects.requireNonNull(mcpEndpointFingerprint, "mcpEndpointFingerprint"); //$NON-NLS-1$
        Objects.requireNonNull(providerEndpointFingerprint, "providerEndpointFingerprint"); //$NON-NLS-1$
        validate(mcpEndpointFingerprint);
        validate(providerEndpointFingerprint);
        if (mcpEndpointFingerprint.isEmpty() && providerEndpointFingerprint.isEmpty()) {
            throw new IllegalArgumentException("missing endpoint fingerprint"); //$NON-NLS-1$
        }
    }

    /** Source-compatible constructor for the original MCP endpoint contract. */
    public SessionContext(String mode, String provider, String model, String endpointFingerprint) {
        this(mode, provider, model, endpointFingerprint, ""); //$NON-NLS-1$
    }

    public static SessionContext fromEndpoint(
            String mode, String provider, String model, String endpoint, String instanceId) {
        return new SessionContext(mode, provider, model, SessionStore.endpointFingerprint(endpoint, instanceId));
    }

    public static SessionContext fromEndpoints(String mode, String provider, String model,
            String mcpEndpoint, String instanceId, String providerEndpoint) {
        return new SessionContext(mode, provider, model,
                SessionStore.endpointFingerprint(mcpEndpoint, instanceId),
                SessionStore.providerEndpointFingerprint(providerEndpoint));
    }

    /** Compatibility alias for callers that still display the original field. */
    public String endpointFingerprint() {
        return mcpEndpointFingerprint.isEmpty()
                ? providerEndpointFingerprint : mcpEndpointFingerprint;
    }

    private static void validate(String fingerprint) {
        if (!fingerprint.isEmpty() && !fingerprint.matches("[0-9a-f]{64}")) { //$NON-NLS-1$
            throw new IllegalArgumentException("invalid endpoint fingerprint"); //$NON-NLS-1$
        }
    }
}
