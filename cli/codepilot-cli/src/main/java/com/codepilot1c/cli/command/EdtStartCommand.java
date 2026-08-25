/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.supervisor.EdtSupervisor;
import com.codepilot1c.cli.supervisor.EdtSupervisor.StartResult;
import com.codepilot1c.cli.supervisor.InstanceRecord;
import com.codepilot1c.cli.supervisor.SupervisorException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "start", mixinStandardHelpOptions = true, description = "Start an owned headless EDT MCP host.")
final class EdtStartCommand implements Callable<Integer> {
    private final RootCommand root;

    @Option(names = "--workspace", required = true, description = "Existing EDT workspace directory.")
    private String workspace;
    @Option(names = "--edt-home", description = "Validated EDT Eclipse home; discovery is used when omitted.")
    private String edtHome;
    @Option(names = "--port", defaultValue = "8765", description = "Loopback MCP HTTP port (default: ${DEFAULT-VALUE}).")
    private int port;
    @Option(names = "--timeout", defaultValue = "120", description = "Readiness timeout in seconds.")
    private long timeoutSeconds;
    @Option(names = "--vm", description = "JVM the Eclipse launcher must use; needed when the EDT "
            + "installation ships no -vm and the host default JVM has a different architecture.")
    private String vm;

    EdtStartCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        try {
            StartResult started = root.services().supervisor().start(new EdtSupervisor.StartRequest(
                    workspace, edtHome, port, Duration.ofSeconds(timeoutSeconds), vm));
            InstanceRecord instance = started.instance();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", "edt start");
            result.put("status", started.state());
            result.put("instance", instance.toJsonValue());
            result.put("httpStatus", started.httpStatus());
            result.put("detail", started.detail());
            CommandOutput.print(root, "ready: " + instance.instanceId() + " " + instance.baseUrl()
                    + " (pid " + instance.pid() + ")", result);
            return ExitCodes.OK;
        } catch (ArithmeticException exception) {
            return failure(new SupervisorException(ExitCodes.USAGE, "invalid_timeout", "timeout is out of range"));
        } catch (SupervisorException exception) {
            return failure(exception);
        }
    }

    private int failure(SupervisorException exception) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "edt start");
        result.put("status", "failed");
        result.put("error", exception.error());
        result.put("message", exception.getMessage());
        if (!exception.details().isEmpty()) result.put("diagnostics", exception.details());
        CommandOutput.print(root, "error[" + exception.error() + "]: " + exception.getMessage()
                + describe(exception), result);
        return exception.exitCode();
    }

    /** Human-readable tail so the non-JSON mode reaches the same log as the JSON diagnostics. */
    private String describe(SupervisorException exception) {
        Object logFile = exception.details().get("logFile");
        Object instanceId = exception.details().get("instanceId");
        if (logFile == null) return "";
        return System.lineSeparator() + "  instance: " + instanceId
                + System.lineSeparator() + "  log: " + logFile;
    }
}
