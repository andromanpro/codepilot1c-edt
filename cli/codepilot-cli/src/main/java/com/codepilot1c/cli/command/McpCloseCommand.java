/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "close", mixinStandardHelpOptions = true, description = "Initialize then close a short-lived MCP session.")
final class McpCloseCommand implements Callable<Integer> {
    private final RootCommand root; private final McpCommand options;
    McpCloseCommand(RootCommand root, McpCommand options) { this.root = root; this.options = options; }
    @Override public Integer call() { return McpCommandSupport.close(root, options); }
}
