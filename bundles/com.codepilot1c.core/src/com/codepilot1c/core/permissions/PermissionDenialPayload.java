/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Builds the shared deterministic payload for permission denials.
 */
public final class PermissionDenialPayload {

    private PermissionDenialPayload() {
    }

    /**
     * Creates a deterministic permission payload. The {@code reason} field is
     * retained for compatibility; new consumers should use {@code reason_code}.
     *
     * @param toolName tool rejected by the gate
     * @param profileId active profile identifier
     * @param resource raw gated resource, or {@code null}
     * @param reasonCode stable machine-readable reason
     * @param layer permission layer that rejected the call
     * @param ruleDescription matching rule description, or {@code null}
     * @return failed tool result with structured denial data
     */
    public static ToolResult denied(
            String toolName, String profileId, String resource, String reasonCode,
            String layer, String ruleDescription) {
        JsonObject data = new JsonObject();
        data.addProperty("error", "permission_denied"); //$NON-NLS-1$ //$NON-NLS-2$
        data.addProperty("tool", toolName); //$NON-NLS-1$
        data.addProperty("profile", profileId); //$NON-NLS-1$
        data.addProperty("reason", reasonCode); //$NON-NLS-1$
        data.addProperty("reason_code", reasonCode); //$NON-NLS-1$
        data.addProperty("layer", layer); //$NON-NLS-1$
        data.addProperty("rule_description",
                ruleDescription != null ? ruleDescription : ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (resource != null) {
            data.addProperty("resource", resource); //$NON-NLS-1$
        }

        StringBuilder message = new StringBuilder()
                .append("Инструмент запрещен политикой профиля: ").append(toolName) //$NON-NLS-1$
                .append(" (profile=").append(profileId); //$NON-NLS-1$
        if (resource != null) {
            message.append(", resource=").append(resource); //$NON-NLS-1$
        }
        message.append(", reason_code=").append(reasonCode) //$NON-NLS-1$
                .append(", layer=").append(layer); //$NON-NLS-1$
        if (ruleDescription != null && !ruleDescription.isBlank()) {
            message.append(", rule_description=").append(ruleDescription); //$NON-NLS-1$
        }
        message.append(')');
        return ToolResult.failure(message.toString(), data);
    }
}
