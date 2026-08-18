/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.discovery.EdtInstallation;

import picocli.CommandLine.Command;

@Command(name = "installations", mixinStandardHelpOptions = true, description = "List validated EDT installations.")
final class EdtInstallationsCommand implements Callable<Integer> {
    private final RootCommand root;
    EdtInstallationsCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        List<EdtInstallation> installations = root.services().discovery().discover();
        List<Map<String, Object>> items = installations.stream().map(this::json).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "edt installations");
        result.put("count", installations.size());
        result.put("installations", items);
        String text = installations.isEmpty() ? "No EDT installations found."
                : installations.stream().map(value -> value.home() + " [" + value.source() + "]")
                        .reduce((left, right) -> left + System.lineSeparator() + right).orElseThrow();
        CommandOutput.print(root, text, result);
        return ExitCodes.OK;
    }

    private Map<String, Object> json(EdtInstallation installation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("home", installation.home());
        value.put("launcher", installation.launcher());
        value.put("source", installation.source());
        return value;
    }
}
