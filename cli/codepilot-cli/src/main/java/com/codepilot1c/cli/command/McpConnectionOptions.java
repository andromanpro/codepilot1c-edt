/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

/** Common safe endpoint and bearer selection used by MCP and agent commands. */
interface McpConnectionOptions {
    String endpoint();
    String instanceId();
    boolean allowInsecureHttp();
    String bearerTokenFile();
}
