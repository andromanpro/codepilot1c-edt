/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Standalone client commands for a running EDT MCP host. */
@Command(name = "mcp", mixinStandardHelpOptions = true,
        description = "Call the MCP endpoint exposed by a running EDT host.")
final class McpCommand implements Callable<Integer> {
    private final RootCommand root;
    @Option(names = "--endpoint", scope = ScopeType.INHERIT,
            description = "MCP endpoint or host base URL; mutually exclusive with --instance-id.")
    private String endpoint;
    @Option(names = "--instance-id", scope = ScopeType.INHERIT,
            description = "Use this registered EDT instance UUID; mutually exclusive with --endpoint.")
    private String instanceId;
    @Option(names = "--allow-insecure-http", scope = ScopeType.INHERIT,
            description = "Allow a non-loopback plain HTTP endpoint.")
    private boolean allowInsecureHttp;
    @Option(names = "--bearer-token-file", scope = ScopeType.INHERIT,
            description = "UTF-8 file containing the bearer token (preferred over environment variables).")
    private String bearerTokenFile;
    @Spec private CommandSpec spec;

    McpCommand(RootCommand root) { this.root = root; }
    RootCommand root() { return root; }
    String endpoint() { return endpoint; }
    String instanceId() { return instanceId; }
    boolean allowInsecureHttp() { return allowInsecureHttp; }
    String bearerTokenFile() { return bearerTokenFile; }

    @Override public Integer call() {
        spec.commandLine().usage(root.services().out());
        return ExitCodes.OK;
    }
}
