/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.io.IOException;

/** Small terminal surface used by the shell loop and scripted tests. */
public interface ShellTerminal extends AutoCloseable {
    /** Reads one line, returning {@code null} for end of input (Ctrl+D on a TTY). */
    String readLine(String prompt);

    void println(String text);

    void flush();

    @Override void close() throws IOException;
}
