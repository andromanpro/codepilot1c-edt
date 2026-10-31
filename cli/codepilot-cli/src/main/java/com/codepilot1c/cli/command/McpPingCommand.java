/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "ping", mixinStandardHelpOptions = true, description = "Initialize a session and send MCP ping.")
final class McpPingCommand implements Callable<Integer> {
    private final RootCommand root; private final McpCommand options;
    McpPingCommand(RootCommand root, McpCommand options) { this.root = root; this.options = options; }
    @Override public Integer call() { return McpCommandSupport.ping(root, options); }
}
