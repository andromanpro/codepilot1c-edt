package com.codepilot1c.core.mcp.host;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-neutral CodePilot MCP contract metadata.
 */
public record McpContractMetadata(
        int contractVersion,
        String pluginVersion,
        String edtVersion,
        String mode,
        String workspace,
        McpReadiness readiness) {

    public McpContractMetadata {
        pluginVersion = valueOrUnknown(pluginVersion);
        edtVersion = valueOrUnknown(edtVersion);
        mode = normalizeMode(mode);
        workspace = valueOrUnknown(workspace);
        readiness = readiness != null ? readiness : McpReadiness.notReady("Readiness is unavailable"); //$NON-NLS-1$
    }

    public Map<String, Object> asCodePilotMap() {
        Map<String, Object> codepilot = new LinkedHashMap<>();
        codepilot.put("contractVersion", contractVersion); //$NON-NLS-1$
        codepilot.put("pluginVersion", pluginVersion); //$NON-NLS-1$
        codepilot.put("edtVersion", edtVersion); //$NON-NLS-1$
        codepilot.put("mode", mode); //$NON-NLS-1$
        codepilot.put("workspace", workspace); //$NON-NLS-1$
        codepilot.put("readiness", readiness.asMetadata()); //$NON-NLS-1$
        return codepilot;
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value; //$NON-NLS-1$
    }

    private static String normalizeMode(String value) {
        return "headless".equalsIgnoreCase(value) ? "headless" : "gui"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
