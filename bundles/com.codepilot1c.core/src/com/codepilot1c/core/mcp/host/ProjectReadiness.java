package com.codepilot1c.core.mcp.host;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Extensible project readiness value for the MCP contract.
 *
 * <p>The current contract intentionally does not discover projects. The type
 * exists so a future WorkspaceProjectBootstrap status provider can add named
 * project states without changing the wire shape.</p>
 */
public record ProjectReadiness(String name, String state) {

    private static final Set<String> VALID_STATES = Set.of("imported", "building", "ready"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    public ProjectReadiness {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project readiness name is required"); //$NON-NLS-1$
        }
        if (!VALID_STATES.contains(state)) {
            throw new IllegalArgumentException("Invalid project readiness state"); //$NON-NLS-1$
        }
    }

    public Map<String, Object> asMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name); //$NON-NLS-1$
        payload.put("state", state); //$NON-NLS-1$
        return payload;
    }
}
