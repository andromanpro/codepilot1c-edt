/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;

import picocli.CommandLine.Command;

@Command(name = "doctor", mixinStandardHelpOptions = true, description = "Run machine-readable CLI and EDT checks.")
final class DoctorCommand implements Callable<Integer> {
    private final RootCommand root;
    DoctorCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        List<Check> checks = new ArrayList<>();
        checks.add(javaCheck());
        checks.add(edtCheck());
        checks.add(configCheck());
        checks.add(endpointCheck());
        boolean healthy = checks.stream().allMatch(Check::passed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "doctor");
        result.put("status", healthy ? "ok" : "failed");
        result.put("checks", checks.stream().map(Check::json).toList());
        String text = checks.stream().map(Check::text)
                .reduce((left, right) -> left + System.lineSeparator() + right).orElse("");
        CommandOutput.print(root, text, result);
        return healthy ? ExitCodes.OK : ExitCodes.EDT_UNAVAILABLE;
    }

    private Check javaCheck() {
        String version = root.services().host().javaVersion();
        int feature = javaFeature(version);
        return new Check("java", feature >= 17, feature >= 17 ? "java_compatible" : "java_too_old",
                "Java " + version + (feature >= 17 ? " is supported" : "; Java 17 or newer is required"));
    }

    private Check edtCheck() {
        int count = root.services().discovery().discover().size();
        return new Check("edt", count > 0, count > 0 ? "edt_found" : "edt_not_found",
                count == 0 ? "No validated EDT installation found" : count + " validated installation(s) found");
    }

    private Check configCheck() {
        Optional<String> configured = root.services().configuration().explicitConfigPath();
        if (configured.isEmpty()) return new Check("config", true, "defaults", "Built-in defaults are active");
        String path = configured.orElseThrow();
        boolean valid = root.services().host().isRegularFile(path) && root.services().host().isReadable(path);
        return new Check("config", valid, valid ? "config_readable" : "config_unreadable",
                valid ? "Configuration is readable: " + path : "Configuration is missing or unreadable: " + path);
    }

    private Check endpointCheck() {
        try {
            URI endpoint = root.services().configuration().endpoint();
            ProbeResult probe = root.services().endpointProbe().probe(endpoint);
            return new Check("endpoint", probe.reachable(), probe.reachable() ? "endpoint_ready" : "endpoint_unavailable",
                    endpoint + " " + probe.detail());
        } catch (Exception exception) {
            return new Check("endpoint", false, "invalid_endpoint", exception.getMessage());
        }
    }

    static int javaFeature(String version) {
        if (version == null) return -1;
        try {
            String[] components = version.split("[._+-]");
            int first = Integer.parseInt(components[0]);
            return first == 1 && components.length > 1 ? Integer.parseInt(components[1]) : first;
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private record Check(String name, boolean passed, String code, String detail) {
        String text() { return name + " " + (passed ? "PASS" : "FAIL") + " " + code + ": " + detail; }
        Map<String, Object> json() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", name);
            value.put("status", passed ? "pass" : "fail");
            value.put("code", code);
            value.put("detail", detail);
            return value;
        }
    }
}
