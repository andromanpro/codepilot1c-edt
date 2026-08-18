/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;

import picocli.CommandLine.Command;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Probe readiness of the configured EDT MCP host.")
final class EdtStatusCommand implements Callable<Integer> {
    private final RootCommand root;
    EdtStatusCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "edt status");
        try {
            URI endpoint = root.services().configuration().endpoint();
            result.put("endpoint", endpoint.toASCIIString());
            ProbeResult probe = root.services().endpointProbe().probe(endpoint);
            result.put("status", probe.reachable() ? "ready" : "unavailable");
            result.put("httpStatus", probe.httpStatus());
            result.put("detail", probe.detail());
            CommandOutput.print(root,
                    (probe.reachable() ? "ready" : "unavailable") + ": " + endpoint + " (" + probe.detail() + ")",
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
}
