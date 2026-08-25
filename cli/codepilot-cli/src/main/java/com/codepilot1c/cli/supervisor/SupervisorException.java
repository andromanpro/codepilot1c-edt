/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic user-facing supervisor failure. */
public final class SupervisorException extends Exception {
    private static final long serialVersionUID = 2L;
    private final int exitCode;
    private final String error;
    private final transient Map<String, Object> details;

    public SupervisorException(int exitCode, String error, String message) {
        this(exitCode, error, message, Map.of());
    }

    /**
     * @param details non-secret, machine-readable identity and diagnostics for this exact failure.
     *     Callers must pass only values that are safe to print: instance identity, workspace, port,
     *     log reference and last readiness probe. Never the launch command line, environment or
     *     credentials.
     */
    public SupervisorException(int exitCode, String error, String message, Map<String, Object> details) {
        super(message);
        this.exitCode = exitCode;
        this.error = error;
        this.details = details == null || details.isEmpty()
                ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public int exitCode() { return exitCode; }
    public String error() { return error; }

    /** Never {@code null}; empty when the failure carries no additional identity. */
    public Map<String, Object> details() { return details; }
}
