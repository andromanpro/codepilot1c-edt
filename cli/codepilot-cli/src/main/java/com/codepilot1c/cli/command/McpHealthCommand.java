/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "health", mixinStandardHelpOptions = true, description = "Read MCP host readiness without creating a session.")
final class McpHealthCommand implements Callable<Integer> {
    private final RootCommand root; private final McpCommand options;
    McpHealthCommand(RootCommand root, McpCommand options) { this.root = root; this.options = options; }
    @Override public Integer call() { return McpCommandSupport.health(root, options); }
}
