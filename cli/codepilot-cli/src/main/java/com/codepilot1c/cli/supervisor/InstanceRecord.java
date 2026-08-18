/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned, non-secret record shared by CLI and the headless host. */
public record InstanceRecord(int schemaVersion, String instanceId, long pid, int port, String baseUrl,
        String workspace, String edtHome, String mode, String owner, Instant startedAt,
        String pluginVersion, String authMode, String logFile, List<String> capabilities) {
    public static final int SCHEMA_VERSION = 1;

    /** Source-compatible constructor for records produced before optional capabilities existed. */
    public InstanceRecord(int schemaVersion, String instanceId, long pid, int port, String baseUrl,
            String workspace, String edtHome, String mode, String owner, Instant startedAt,
            String pluginVersion, String authMode, String logFile) {
        this(schemaVersion, instanceId, pid, port, baseUrl, workspace, edtHome, mode, owner,
                startedAt, pluginVersion, authMode, logFile, List.of());
    }

    public InstanceRecord {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported schemaVersion");
        Objects.requireNonNull(instanceId, "instanceId");
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(edtHome, "edtHome");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(startedAt, "startedAt");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        if (capabilities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("capabilities must be non-blank strings");
        }
        validateBaseUrl(baseUrl, port);
    }

    public Map<String, Object> toJsonValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("instanceId", instanceId);
        value.put("pid", pid);
        value.put("port", port);
        value.put("baseUrl", baseUrl);
        value.put("workspace", workspace);
        value.put("edtHome", edtHome);
        value.put("mode", mode);
        value.put("owner", owner);
        value.put("startedAt", startedAt.toString());
        if (pluginVersion != null && !pluginVersion.isBlank()) value.put("pluginVersion", pluginVersion);
        if (authMode != null && !authMode.isBlank()) value.put("authMode", authMode);
        if (logFile != null && !logFile.isBlank()) value.put("logFile", logFile);
        if (capabilities.contains("llm.v1")) value.put("llmBrokerVersion", 1);
        return value;
    }

    private static void validateBaseUrl(String value, int port) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("invalid baseUrl"); }
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback || uri.getPort() != port
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be loopback HTTP on the registered port");
        }
    }
}
