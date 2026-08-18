package com.codepilot1c.core.mcp.host;

/**
 * Supplies the snapshot advertised by the MCP contract.
 *
 * <p>The interface keeps runtime discovery out of the JSON-RPC router and
 * makes the contract independently testable.</p>
 */
@FunctionalInterface
public interface McpContractMetadataProvider {

    McpContractMetadata snapshot();
}
