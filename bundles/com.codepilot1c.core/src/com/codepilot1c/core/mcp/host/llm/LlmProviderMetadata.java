package com.codepilot1c.core.mcp.host.llm;

/**
 * Safe, allowlisted metadata describing the active provider to a connected
 * client. Provider configuration objects must never be used as wire payloads.
 */
public record LlmProviderMetadata(String id, String name, String type, String model,
        boolean streamingEnabled) {

    public LlmProviderMetadata {
        id = safe(id);
        name = safe(name);
        type = safe(type);
        model = safe(model);
    }

    private static String safe(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }
}
