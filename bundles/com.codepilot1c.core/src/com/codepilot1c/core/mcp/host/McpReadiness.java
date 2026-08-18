package com.codepilot1c.core.mcp.host;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Immutable readiness result shared by the MCP initialize metadata and HTTP
 * readiness endpoint.
 */
public record McpReadiness(
        boolean ready,
        String reason,
        Map<String, String> services,
        List<String> projects) {

    private static final Set<String> VALID_STATES = Set.of("ready", "starting", "degraded"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    public McpReadiness {
        reason = reason == null ? "" : reason;
        services = normalizeStates(services, "services"); //$NON-NLS-1$
        projects = normalizeProjectStates(projects);
        if (ready) {
            reason = "";
        }
    }

    public static McpReadiness available() {
        return new McpReadiness(true, "", //$NON-NLS-1$
                Map.of("mcp", "ready", "edt", "ready"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("ready")); //$NON-NLS-1$
    }

    public static McpReadiness notReady(String reason) {
        return new McpReadiness(false, reason,
                Map.of("mcp", "ready", "edt", "degraded"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("degraded")); //$NON-NLS-1$
    }

    public static McpReadiness starting(String reason) {
        return new McpReadiness(false, reason,
                Map.of("mcp", "ready", "edt", "starting"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("starting")); //$NON-NLS-1$
    }

    /**
     * Returns the stable JSON shape used in the experimental MCP block.
     */
    public Map<String, Object> asMetadata() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("services", services); //$NON-NLS-1$
        payload.put("projects", projects); //$NON-NLS-1$
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

    private static Map<String, String> normalizeStates(Map<String, String> values, String fieldName) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((name, state) -> {
                if (name == null || name.isBlank() || !VALID_STATES.contains(state)) {
                    throw new IllegalArgumentException("Invalid " + fieldName + " readiness state"); //$NON-NLS-1$
                }
                normalized.put(name, state);
            });
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static List<String> normalizeProjectStates(List<String> values) {
        if (values == null) {
            return List.of();
        }
        values.forEach(state -> {
            if (!VALID_STATES.contains(state)) {
                throw new IllegalArgumentException("Invalid projects readiness state"); //$NON-NLS-1$
            }
        });
        return List.copyOf(values);
    }
}
