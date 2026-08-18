package com.codepilot1c.core.mcp.host;

import java.util.Optional;

/**
 * Runtime boundary used by MCP metadata discovery.
 *
 * <p>Implementations may be backed by EDT services, but the contract layer
 * itself does not depend on UI or workbench APIs.</p>
 */
public interface McpRuntimeInfoGateway {

    Optional<String> edtVersion();

    Optional<String> workspace();

    String mode();

    McpReadiness readiness();
}
