/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import com.codepilot1c.runtime.agent.CancellationToken;
import com.codepilot1c.runtime.agent.ToolApprover;
import com.codepilot1c.runtime.agent.ToolCall;
import com.codepilot1c.runtime.agent.ToolDefinition;

/** Interactive y/n/a tool approval with allow-for-this-session memory by tool name. */
public final class ConfirmationPrompter implements ToolApprover {
    private final ShellTerminal terminal;
    private final TerminalReader reader;
    private final UnaryOperator<String> redactor;
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();

    public ConfirmationPrompter(ShellTerminal terminal, TerminalReader reader,
            UnaryOperator<String> redactor) {
        this.terminal = java.util.Objects.requireNonNull(terminal, "terminal");
        this.reader = java.util.Objects.requireNonNull(reader, "reader");
        this.redactor = java.util.Objects.requireNonNull(redactor, "redactor");
    }

    @Override
    public CompletableFuture<Decision> approve(
            ToolCall call, ToolDefinition definition, CancellationToken cancellation) {
        if (!DangerousToolFallback.requiresConfirmation(definition)
                || sessionAllowed.contains(definition.name())) {
            return CompletableFuture.completedFuture(Decision.allow());
        }
        if (cancellation.isCancelled()) return cancelled();
        terminal.println(safe("Approval required: " + DangerousToolFallback.displayName(definition)
                + " [" + definition.name() + ", "
                + DangerousToolFallback.riskLabel(definition) + "]"));
        terminal.flush();
        while (!cancellation.isCancelled()) {
            String answer = reader.readLine("Allow? [y]es/[n]o/[a]ll session: ", cancellation);
            if (answer == null) return CompletableFuture.completedFuture(
                    Decision.deny("Confirmation denied by end of input"));
            switch (answer.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "y", "yes":
                    return CompletableFuture.completedFuture(Decision.allow());
                case "a", "all":
                    sessionAllowed.add(definition.name());
                    return CompletableFuture.completedFuture(Decision.allow());
                case "n", "no":
                    return CompletableFuture.completedFuture(
                            Decision.deny("Confirmation denied by user"));
                default:
                    terminal.println("Please answer y, n, or a.");
                    terminal.flush();
                    break;
            }
        }
        return cancelled();
    }

    public void resetSession() {
        sessionAllowed.clear();
    }

    public boolean isAllowedForSession(String toolName) {
        return sessionAllowed.contains(toolName);
    }

    private static CompletableFuture<Decision> cancelled() {
        CompletableFuture<Decision> future = new CompletableFuture<>();
        future.cancel(false);
        return future;
    }

    private String safe(String value) {
        return java.util.Objects.requireNonNull(redactor.apply(value), "redactor result");
    }
}
