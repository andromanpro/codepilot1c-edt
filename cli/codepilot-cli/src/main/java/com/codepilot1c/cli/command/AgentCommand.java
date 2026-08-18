/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Standalone bounded agent commands. */
@Command(name = "agent", mixinStandardHelpOptions = true,
        description = "Run the standalone provider-neutral agent loop.")
final class AgentCommand implements Callable<Integer> {
    private final RootCommand root;
    @Spec private CommandSpec spec;

    AgentCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        spec.commandLine().usage(root.services().out());
        return ExitCodes.OK;
    }
}
