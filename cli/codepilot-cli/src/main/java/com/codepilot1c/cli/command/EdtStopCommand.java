/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.supervisor.EdtSupervisor;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StopItem;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StopResult;
import com.codepilot1c.cli.supervisor.SupervisorException;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "stop", mixinStandardHelpOptions = true, description = "Stop CLI-owned headless EDT instances.")
final class EdtStopCommand implements Callable<Integer> {
    private final RootCommand root;

    static final class Target {
        @Option(names = "--id", description = "Registered instance UUID.") String id;
        @Option(names = "--all", description = "Stop every instance whose registry owner is cli.") boolean all;
    }

    @ArgGroup(exclusive = true, multiplicity = "1") private Target target;
    @Option(names = "--force", description = "Force termination if graceful process destruction times out.")
    private boolean force;
    @Option(names = "--timeout", defaultValue = "10", description = "Per-stage stop timeout in seconds.")
    private long timeoutSeconds;

    EdtStopCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        try {
            StopResult stopped = root.services().supervisor().stop(new EdtSupervisor.StopRequest(
                    target.id, target.all, force, Duration.ofSeconds(timeoutSeconds)));
            List<Map<String, Object>> items = stopped.items().stream().map(EdtStopCommand::json).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", "edt stop");
            result.put("status", stopped.complete() ? "stopped" : "failed");
            result.put("count", items.size());
            result.put("instances", items);
            String text = items.isEmpty() ? "No CLI-owned EDT instances found."
                    : stopped.items().stream().map(item -> item.state() + ": " + item.instanceId()
                            + " (pid " + item.pid() + ")")
                            .reduce((left, right) -> left + System.lineSeparator() + right).orElseThrow();
            CommandOutput.print(root, text, result);
            return stopped.complete() ? ExitCodes.OK : ExitCodes.EDT_UNAVAILABLE;
        } catch (ArithmeticException exception) {
            return failure(new SupervisorException(ExitCodes.USAGE, "invalid_timeout", "timeout is out of range"));
        } catch (SupervisorException exception) {
            return failure(exception);
        }
    }

    private int failure(SupervisorException exception) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "edt stop");
        result.put("status", "failed");
        result.put("error", exception.error());
        result.put("message", exception.getMessage());
        CommandOutput.print(root, "error[" + exception.error() + "]: " + exception.getMessage(), result);
        return exception.exitCode();
    }

    private static Map<String, Object> json(StopItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("instanceId", item.instanceId());
        value.put("pid", item.pid());
        value.put("state", item.state());
        value.put("stopped", item.stopped());
        return value;
    }
}
