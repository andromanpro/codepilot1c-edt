/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;

import com.codepilot1c.cli.CliServices;
import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.render.OutputMode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Root of the standalone command surface. */
@Command(name = "codepilot", mixinStandardHelpOptions = true,
        description = "Standalone harness for CodePilot and 1C:EDT.")
public final class RootCommand implements Callable<Integer> {
    private final CliServices services;

    @Option(names = "--output", defaultValue = "TEXT", scope = ScopeType.INHERIT,
            description = "Output format: ${COMPLETION-CANDIDATES}.")
    private OutputMode outputMode;

    @Spec private CommandSpec spec;

    public RootCommand(CliServices services) { this.services = services; }
    public CliServices services() { return services; }
    public OutputMode outputMode() { return outputMode; }

    @Override public Integer call() {
        spec.commandLine().usage(services.out());
        return ExitCodes.OK;
    }

    public static CommandLine commandLine(CliServices services) {
        RootCommand root = new RootCommand(services);
        CommandLine commandLine = new CommandLine(root);
        commandLine.addSubcommand("version", new VersionCommand(root));
        commandLine.addSubcommand("doctor", new DoctorCommand(root));
        EdtCommand edt = new EdtCommand(root);
        CommandLine edtLine = new CommandLine(edt);
        edtLine.addSubcommand("status", new EdtStatusCommand(root));
        edtLine.addSubcommand("installations", new EdtInstallationsCommand(root));
        edtLine.addSubcommand("start", new EdtStartCommand(root));
        edtLine.addSubcommand("stop", new EdtStopCommand(root));
        commandLine.addSubcommand("edt", edtLine);
        McpCommand mcp = new McpCommand(root);
        CommandLine mcpLine = new CommandLine(mcp);
        mcpLine.addSubcommand("health", new McpHealthCommand(root, mcp));
        mcpLine.addSubcommand("initialize", new McpInitializeCommand(root, mcp));
        mcpLine.addSubcommand("tools", new McpToolsCommand(root, mcp));
        mcpLine.addSubcommand("call", new McpCallCommand(root, mcp));
        mcpLine.addSubcommand("ping", new McpPingCommand(root, mcp));
        mcpLine.addSubcommand("close", new McpCloseCommand(root, mcp));
        commandLine.addSubcommand("mcp", mcpLine);
        AgentCommand agent = new AgentCommand(root);
        CommandLine agentLine = new CommandLine(agent);
        agentLine.addSubcommand("run", new AgentRunCommand(root));
        commandLine.addSubcommand("agent", agentLine);
        return commandLine;
    }
}
