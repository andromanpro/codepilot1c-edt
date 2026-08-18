/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

/** Stable process exit codes shared by all CLI commands. */
public final class ExitCodes {
    public static final int OK = 0;
    public static final int FAILURE = 1;
    public static final int USAGE = 2;
    /** Reserved for missing or rejected provider credentials. */
    public static final int AUTH = 3;
    /** EDT is absent, not ready, or cannot perform the requested operation. */
    public static final int EDT_UNAVAILABLE = 4;
    private ExitCodes() { }
}
