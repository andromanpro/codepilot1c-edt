/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

/** Stable process exit codes shared by all CLI commands. */
public final class ExitCodes {
    public static final int OK = 0;
    public static final int FAILURE = 1;
    public static final int USAGE = 2;
    public static final int UNAVAILABLE = 3;
    public static final int NOT_IMPLEMENTED = 4;
    private ExitCodes() { }
}
