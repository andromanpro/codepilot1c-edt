/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

/** Deterministic user-facing supervisor failure. */
public final class SupervisorException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int exitCode;
    private final String error;

    public SupervisorException(int exitCode, String error, String message) {
        super(message);
        this.exitCode = exitCode;
        this.error = error;
    }

    public int exitCode() { return exitCode; }
    public String error() { return error; }
}
