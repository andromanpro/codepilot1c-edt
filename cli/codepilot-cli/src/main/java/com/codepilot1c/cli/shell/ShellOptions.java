/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

/** Parsed shell settings reserved for the later controller implementation. */
public record ShellOptions(Mode mode, String instanceId, String mcpEndpoint,
        String mcpBearerTokenFile, boolean allowInsecureHttp,
        String provider, String providerEndpoint, String model, String providerApiKeyFile,
        boolean providerAllowInsecureHttp, int maxSteps, long turnTimeoutSeconds,
        String systemPromptFile) {
    public enum Mode { AUTO, CONNECTED, STANDALONE }
}
