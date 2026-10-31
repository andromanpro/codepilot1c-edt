package com.codepilot1c.core.mcp.host;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts the metadata provider to the MCP and HTTP response shapes.
 */
public final class McpContractMetadataService {

    private final McpContractMetadataProvider provider;

    public McpContractMetadataService() {
        this(new DefaultMcpContractMetadataProvider());
    }

    public McpContractMetadataService(McpContractMetadataProvider provider) {
        this.provider = provider != null ? provider : new DefaultMcpContractMetadataProvider();
    }

    public Map<String, Object> experimentalMetadata() {
        Map<String, Object> experimental = new LinkedHashMap<>();
        experimental.put("codepilot", snapshot().asCodePilotMap()); //$NON-NLS-1$
        return experimental;
    }

    public McpReadiness readiness() {
        return snapshot().readiness();
    }

    private McpContractMetadata snapshot() {
        try {
            McpContractMetadata metadata = provider.snapshot();
            return metadata != null
                ? metadata
                : new McpContractMetadata(1, null, null, null, null,
                    McpReadiness.notReady("Contract metadata is unavailable")); //$NON-NLS-1$
        } catch (RuntimeException e) {
            return new McpContractMetadata(1, null, null, null, null,
                McpReadiness.notReady("Contract metadata is unavailable")); //$NON-NLS-1$
        }
    }
}
