/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;

import picocli.CommandLine.Command;

@Command(name = "version", mixinStandardHelpOptions = true, description = "Print CLI version.")
final class VersionCommand implements Callable<Integer> {
    private final RootCommand root;
    VersionCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("command", "version");
        value.put("version", root.services().version());
        CommandOutput.print(root, "codepilot " + root.services().version(), value);
        return ExitCodes.OK;
    }
}
