/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

/** Extension seam for the later shell controller; C1 deliberately supplies only a placeholder. */
@FunctionalInterface
public interface ShellInputHandler {
    void handle(String input, ShellOptions options, ShellTerminal terminal) throws Exception;
}
