package com.codepilot1c.core.mcp.host;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable readiness result shared by the MCP initialize metadata and HTTP
 * readiness endpoint.
 */
public record McpReadiness(
        boolean ready,
        String reason,
        String services,
        List<ProjectReadiness> projects) {

    private static final Set<String> VALID_SERVICE_STATES = Set.of("ready", "starting", "degraded"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    public McpReadiness {
        reason = reason == null ? "" : reason;
        services = normalizeServiceState(services);
        projects = projects == null ? List.of() : List.copyOf(projects);
        if (ready) {
            reason = "";
        }
    }

    public static McpReadiness available() {
        return new McpReadiness(true, "", //$NON-NLS-1$
                "ready", //$NON-NLS-1$
                List.of());
    }

    public static McpReadiness notReady(String reason) {
        return new McpReadiness(false, reason,
                "degraded", //$NON-NLS-1$
                List.of());
    }

    public static McpReadiness starting(String reason) {
        return new McpReadiness(false, reason,
                "starting", //$NON-NLS-1$
                List.of());
    }

    /**
     * Returns the stable JSON shape used in the experimental MCP block.
     */
    public Map<String, Object> asMetadata() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("services", services); //$NON-NLS-1$
        payload.put("projects", projects.stream().map(ProjectReadiness::asMap).collect(Collectors.toList())); //$NON-NLS-1$
        payload.put("status", ready ? "ready" : "not_ready"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("ready", Boolean.valueOf(ready)); //$NON-NLS-1$
        if (!ready) {
            payload.put("reason", reason); //$NON-NLS-1$
        }
        return payload;
    }

    /**
     * Returns the stable JSON shape used by GET /health/ready.
     */
    public Map<String, Object> asHealthResponse() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", ready ? "ready" : "not_ready"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("ready", Boolean.valueOf(ready)); //$NON-NLS-1$
        if (!ready) {
            payload.put("reason", reason); //$NON-NLS-1$
        }
        return payload;
    }

    private static String normalizeServiceState(String value) {
        if (!VALID_SERVICE_STATES.contains(value)) {
            throw new IllegalArgumentException("Invalid services readiness state"); //$NON-NLS-1$
        }
        return value;
    }
}
