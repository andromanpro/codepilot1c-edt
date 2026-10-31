/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "edt", mixinStandardHelpOptions = true, description = "Inspect or control the local EDT host.")
final class EdtCommand implements Callable<Integer> {
    private final RootCommand root;
    @Spec private CommandSpec spec;
    EdtCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        spec.commandLine().usage(root.services().out());
        return ExitCodes.OK;
    }
}
