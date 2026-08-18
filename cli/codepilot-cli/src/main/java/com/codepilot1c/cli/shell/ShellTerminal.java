/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.io.IOException;

/** Small terminal surface used by the shell loop and scripted tests. */
public interface ShellTerminal extends AutoCloseable {
    /** Reads one line, returning {@code null} for end of input (Ctrl+D on a TTY). */
    String readLine(String prompt);

    /**
     * Actively aborts the current {@link #readLine(String)} call, if any.
     * Implementations must make the read return or throw promptly; callers use
     * this guarantee to transfer terminal-reader ownership without a JLine race.
     */
    default void abortRead() { }

    /** Whether this terminal can safely render ANSI control sequences. */
    default boolean ansiCapable() { return false; }

    /** Whether this is a dumb or redirected terminal. */
    default boolean dumb() { return true; }

    void println(String text);

    void flush();

    @Override void close() throws IOException;
}
