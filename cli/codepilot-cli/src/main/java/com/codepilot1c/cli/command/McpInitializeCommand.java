/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "initialize", mixinStandardHelpOptions = true, description = "Negotiate MCP protocol and verify session creation.")
final class McpInitializeCommand implements Callable<Integer> {
    private final RootCommand root; private final McpCommand options;
    McpInitializeCommand(RootCommand root, McpCommand options) { this.root = root; this.options = options; }
    @Override public Integer call() { return McpCommandSupport.initialize(root, options); }
}
