package com.codepilot1c.core.mcp.host;

import java.util.Optional;

/**
 * Supplies the build version of the EDT product itself.
 */
@FunctionalInterface
public interface McpEdtVersionSupplier {

    Optional<String> get();
}
