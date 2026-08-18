/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.io.IOException;

/** Injectable boundary between CLI routing and the process terminal. */
public interface TerminalFactory {
    /** Whether standard input and output are attached to an interactive terminal. */
    boolean isInteractive();

    /** Opens a terminal for one shell invocation. */
    ShellTerminal open() throws IOException;
}
