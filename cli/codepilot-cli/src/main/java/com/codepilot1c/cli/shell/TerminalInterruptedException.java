/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

/** A terminal read interrupted by Ctrl+C, with the line JLine discarded. */
public final class TerminalInterruptedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String partialLine;

    public TerminalInterruptedException(String partialLine) {
        this.partialLine = partialLine == null ? "" : partialLine;
    }

    public String partialLine() {
        return partialLine;
    }
}
