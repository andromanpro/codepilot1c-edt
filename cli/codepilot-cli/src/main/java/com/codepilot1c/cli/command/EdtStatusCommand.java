/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StatusItem;
import com.codepilot1c.cli.supervisor.SupervisorException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Probe readiness of the configured EDT MCP host.")
final class EdtStatusCommand implements Callable<Integer> {
    private final RootCommand root;
    @Option(names = "--all", description = "Inspect all registered EDT instances, including external and stale records.")
    private boolean all;
    EdtStatusCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        if (all) return allInstances();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "edt status");
        try {
            URI endpoint = root.services().configuration().endpoint();
            result.put("endpoint", endpoint.toASCIIString());
            ProbeResult probe = root.services().endpointProbe().probe(endpoint);
            result.put("status", probe.reachable() ? "ready" : "degraded");
            result.put("httpStatus", probe.httpStatus());
            result.put("detail", probe.detail());
            CommandOutput.print(root,
                    (probe.reachable() ? "ready" : "degraded") + ": " + endpoint + " (" + probe.detail() + ")",
                    result);
            return probe.reachable() ? ExitCodes.OK : ExitCodes.EDT_UNAVAILABLE;
        } catch (Exception exception) {
            result.put("endpoint", "<invalid>");
            result.put("status", "invalid_configuration");
            result.put("error", "invalid_endpoint");
            result.put("detail", exception.getMessage());
            CommandOutput.print(root, "error[invalid_endpoint]: " + exception.getMessage(), result);
            return ExitCodes.USAGE;
        }
    }

    private int allInstances() {
        try {
            List<StatusItem> statuses = root.services().supervisor().statusAll();
            List<Map<String, Object>> instances = statuses.stream().map(this::json).toList();
            boolean healthy = statuses.stream().allMatch(value -> "ready".equals(value.state()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", "edt status");
            result.put("status", healthy ? "ready" : "degraded");
            result.put("count", instances.size());
            result.put("instances", instances);
            String text = statuses.isEmpty() ? "No registered EDT instances."
                    : statuses.stream().map(value -> value.state() + ": " + value.instance().instanceId()
                            + " " + value.instance().baseUrl() + " (pid " + value.instance().pid() + ")")
                            .reduce((left, right) -> left + System.lineSeparator() + right).orElseThrow();
            CommandOutput.print(root, text, result);
            return healthy ? ExitCodes.OK : ExitCodes.EDT_UNAVAILABLE;
        } catch (SupervisorException exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", "edt status");
            result.put("status", "failed");
            result.put("error", exception.error());
            result.put("message", exception.getMessage());
            CommandOutput.print(root, "error[" + exception.error() + "]: " + exception.getMessage(), result);
            return exception.exitCode();
        }
    }

    private Map<String, Object> json(StatusItem status) {
        Map<String, Object> value = new LinkedHashMap<>(status.instance().toJsonValue());
        value.put("state", status.state());
        value.put("pidAlive", status.pidAlive());
        value.put("httpStatus", status.httpStatus());
        value.put("detail", status.detail());
        return value;
    }
}
