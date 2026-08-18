/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import com.codepilot1c.runtime.agent.ToolAnnotations;
import com.codepilot1c.runtime.agent.ToolDefinition;

/** Conservative approval policy for incomplete or legacy MCP annotations. */
public final class DangerousToolFallback {
    private DangerousToolFallback() { }

    public static boolean requiresConfirmation(ToolDefinition definition) {
        return definition.annotations().map(DangerousToolFallback::risky).orElse(true);
    }

    public static String riskLabel(ToolDefinition definition) {
        return definition.annotations().map(annotation -> {
            if (annotation.destructive()) return "destructive";
            if (annotation.requiresConfirmation()) return "confirmation required";
            if (!annotation.readOnly()) return "mutating";
            return "read-only";
        }).orElse("unknown risk");
    }

    public static String displayName(ToolDefinition definition) {
        return definition.annotations().map(ToolAnnotations::title)
                .filter(value -> !value.isBlank()).orElse(definition.name());
    }

    private static boolean risky(ToolAnnotations annotation) {
        return annotation.destructive() || annotation.requiresConfirmation() || !annotation.readOnly();
    }
}
