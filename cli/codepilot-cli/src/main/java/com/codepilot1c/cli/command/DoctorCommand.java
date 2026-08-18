/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.shell.broker.BrokerClient;
import com.codepilot1c.cli.shell.broker.BrokerInfo;
import com.codepilot1c.cli.supervisor.DefaultSupervisorFileSystem;
import com.codepilot1c.cli.supervisor.InstanceRecord;
import com.codepilot1c.cli.supervisor.InstanceRegistry;
import com.codepilot1c.cli.supervisor.InstanceRegistry.BrokerAdvertisement;
import com.codepilot1c.runtime.agent.AgentModelException;

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
        checks.add(brokerCheck());
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
                    "Configured MCP endpoint: " + safeProbeDetail(probe));
        } catch (Exception exception) {
            return new Check("endpoint", false, "invalid_endpoint", "Configured MCP endpoint is invalid");
        }
    }

    private Check brokerCheck() {
        URI endpoint;
        try { endpoint = root.services().configuration().endpoint(); }
        catch (Exception exception) {
            return new Check("broker", false, "broker_invalid_endpoint",
                    "Cannot probe the EDT LLM broker until the MCP endpoint is valid");
        }
        if (brokerAdvertisement(endpoint) == BrokerAdvertisement.NOT_ADVERTISED) {
            return new Check("broker", true, "broker_not_advertised",
                    "The matching EDT record explicitly reports no llm.v1 broker; this remains supported");
        }

        char[] token = brokerToken();
        try (BrokerClient broker = new BrokerClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                endpoint, token, false, BrokerClient.DEFAULT_PROBE_TIMEOUT,
                BrokerClient.DEFAULT_REQUEST_TIMEOUT)) {
            BrokerInfo info = broker.probe().toCompletableFuture().get();
            if (!info.chat() || !info.streaming() || !info.provider().streamingEnabled()) {
                return new Check("broker", false, "broker_not_ready",
                        "EDT LLM broker is reachable but streaming chat is unavailable");
            }
            return new Check("broker", true, "broker_ready",
                    "EDT LLM broker is reachable and an active provider is available");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Check("broker", false, "broker_interrupted", "EDT LLM broker probe was interrupted");
        } catch (ExecutionException exception) {
            return brokerFailure(exception.getCause());
        } catch (RuntimeException exception) {
            return brokerFailure(exception);
        } finally {
            if (token != null) Arrays.fill(token, '\0');
        }
    }

    private BrokerAdvertisement brokerAdvertisement(URI endpoint) {
        Path directory = Path.of(root.services().host().userHome(), ".codepilot1c", "instances");
        InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(), directory);
        try {
            List<InstanceRegistry.Entry> matching = registry.listEntries().stream()
                    .filter(entry -> sameOrigin(endpoint, entry.record()))
                    .toList();
            if (matching.stream().anyMatch(entry ->
                    entry.brokerAdvertisement() == BrokerAdvertisement.ADVERTISED)) {
                return BrokerAdvertisement.ADVERTISED;
            }
            if (!matching.isEmpty() && matching.stream().allMatch(entry ->
                    entry.brokerAdvertisement() == BrokerAdvertisement.NOT_ADVERTISED)) {
                return BrokerAdvertisement.NOT_ADVERTISED;
            }
            return BrokerAdvertisement.UNSPECIFIED;
        } catch (Exception ignored) {
            return BrokerAdvertisement.UNSPECIFIED;
        }
    }

    private static boolean sameOrigin(URI endpoint, InstanceRecord record) {
        try {
            URI registered = URI.create(record.baseUrl());
            return endpoint.getScheme().equalsIgnoreCase(registered.getScheme())
                    && sameHost(endpoint.getHost(), registered.getHost())
                    && effectivePort(endpoint) == effectivePort(registered);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean sameHost(String left, String right) {
        if (left.equalsIgnoreCase(right)) return true;
        return loopback(left) && loopback(right);
    }

    private static boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static int effectivePort(URI endpoint) {
        if (endpoint.getPort() >= 0) return endpoint.getPort();
        return "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
    }

    private char[] brokerToken() {
        String value = first(root.services().host().systemProperty("codepilot.mcp.bearerToken"),
                root.services().host().environment("CODEPILOT_MCP_BEARER_TOKEN"));
        return value == null ? null : value.toCharArray();
    }

    private static String first(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    private static Check brokerFailure(Throwable failure) {
        if (failure instanceof AgentModelException modelFailure) {
            if (modelFailure.kind() == AgentModelException.Kind.HTTP) {
                return switch (modelFailure.httpStatus()) {
                    case 401, 403 -> new Check("broker", false, "broker_authentication_failed",
                            "EDT LLM broker rejected authentication; verify the MCP bearer token");
                    case 404 -> new Check("broker", true, "broker_not_advertised",
                            "The configured endpoint does not expose llm.v1; older plugins remain supported");
                    case 409 -> new Check("broker", false, "broker_busy",
                            "EDT LLM broker is busy with another request; retry when it is idle");
                    case 503 -> new Check("broker", false, "provider_unavailable",
                            "No active LLM provider is available; configure and select a provider in EDT");
                    default -> new Check("broker", false, "broker_http_error",
                            "EDT LLM broker returned HTTP " + modelFailure.httpStatus());
                };
            }
            if (modelFailure.kind() == AgentModelException.Kind.MALFORMED_RESPONSE) {
                return new Check("broker", false, "broker_protocol_error",
                        "EDT LLM broker returned an incompatible capability response");
            }
        }
        return new Check("broker", false, "broker_unreachable",
                "The configured EDT LLM broker is unreachable; verify EDT and MCP connectivity");
    }

    private static String safeProbeDetail(ProbeResult probe) {
        if (probe.httpStatus() > 0) return "HTTP " + probe.httpStatus();
        if ("interrupted".equals(probe.detail())) return "interrupted";
        return "unreachable";
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
