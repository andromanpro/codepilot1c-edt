/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.shell.ShellInputHandler;
import com.codepilot1c.cli.shell.ShellOptions;
import com.codepilot1c.cli.shell.ShellOptions.Mode;
import com.codepilot1c.cli.shell.ShellTerminal;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Interactive shell entry point; conversational execution is added in later waves. */
@Command(name = "shell", mixinStandardHelpOptions = true, hidden = true,
        description = "Open the interactive CodePilot shell.")
public final class ShellCommand implements Callable<Integer> {
    private static final String BANNER = "CodePilot shell (foundation)";
    private static final String HELP = "Commands: /help, /exit";

    private final RootCommand root;
    private final ShellInputHandler inputHandler;

    @Option(names = "--mode", defaultValue = "AUTO",
            description = "Shell mode: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    private Mode mode;
    @Option(names = { "--instance-id", "--instance" }, description = "Reserved EDT instance UUID.")
    private String instanceId;
    @Option(names = { "--mcp-endpoint", "--endpoint" }, description = "Reserved MCP endpoint URL.")
    private String mcpEndpoint;
    @Option(names = "--mcp-bearer-token-file", description = "Reserved MCP bearer-token file.")
    private String mcpBearerTokenFile;
    @Option(names = "--allow-insecure-http", description = "Reserved non-loopback MCP HTTP opt-in.")
    private boolean allowInsecureHttp;
    @Option(names = "--provider", description = "Reserved provider identifier.")
    private String provider;
    @Option(names = "--provider-endpoint", description = "Reserved provider base endpoint.")
    private String providerEndpoint;
    @Option(names = "--model", description = "Reserved provider model.")
    private String model;
    @Option(names = "--provider-api-key-file", description = "Reserved provider API-key file.")
    private String providerApiKeyFile;
    @Option(names = "--provider-allow-insecure-http", description = "Reserved provider HTTP opt-in.")
    private boolean providerAllowInsecureHttp;
    @Option(names = "--max-steps", defaultValue = "16", description = "Reserved maximum model steps.")
    private int maxSteps;
    @Option(names = "--turn-timeout", defaultValue = "300",
            description = "Reserved per-turn timeout in seconds.")
    private long turnTimeoutSeconds;
    @Option(names = "--system-prompt-file", description = "Reserved UTF-8 system-prompt file.")
    private String systemPromptFile;

    public ShellCommand(RootCommand root) {
        this(root, (input, options, terminal) ->
                terminal.println("Shell turns are not available yet; type /help or /exit."));
    }

    public ShellCommand(RootCommand root, ShellInputHandler inputHandler) {
        this.root = root;
        this.inputHandler = inputHandler;
    }

    @Override public Integer call() throws Exception {
        ShellOptions options = options();
        try (ShellTerminal terminal = root.services().terminalFactory().open()) {
            terminal.println(BANNER);
            terminal.println("Type /help for commands or /exit to leave.");
            terminal.flush();
            while (true) {
                String input = terminal.readLine("codepilot> ");
                if (input == null || "/exit".equals(input.trim())) return ExitCodes.OK;
                if ("/help".equals(input.trim())) {
                    terminal.println(HELP);
                } else {
                    inputHandler.handle(input, options, terminal);
                }
                terminal.flush();
            }
        }
    }

    private ShellOptions options() {
        return new ShellOptions(mode, instanceId, mcpEndpoint, mcpBearerTokenFile,
                allowInsecureHttp, provider, providerEndpoint, model, providerApiKeyFile,
                providerAllowInsecureHttp, maxSteps, turnTimeoutSeconds, systemPromptFile);
    }
}
