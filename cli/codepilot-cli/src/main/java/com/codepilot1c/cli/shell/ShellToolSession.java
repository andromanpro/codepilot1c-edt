/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolRuntime;

/** Refreshable/reinitializable MCP session boundary owned by the shell. */
public interface ShellToolSession extends AutoCloseable {
    ToolRuntime runtime();
    CompletionStage<List<ToolDefinition>> refresh();
    CompletionStage<Void> ping();
    CompletionStage<ShellToolSession> reinitialize();
    boolean isExpired(Throwable failure);
    @Override void close();
}
