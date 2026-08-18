/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;

import picocli.CommandLine.Command;

@Command(mixinStandardHelpOptions = true, description = "Control command reserved for the EDT supervisor.")
final class EdtLifecycleCommand implements Callable<Integer> {
    private final RootCommand root;
    private final String operation;
    EdtLifecycleCommand(RootCommand root, String operation) { this.root = root; this.operation = operation; }

    @Override public Integer call() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "edt " + operation);
        result.put("status", "not_implemented");
        result.put("error", "supervisor_unavailable");
        result.put("message", "EDT process supervision is not implemented in this build.");
        CommandOutput.print(root,
                "error[supervisor_unavailable]: EDT process supervision is not implemented in this build.", result);
        return ExitCodes.EDT_UNAVAILABLE;
    }
}
