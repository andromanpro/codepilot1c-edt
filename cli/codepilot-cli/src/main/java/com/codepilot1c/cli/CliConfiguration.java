/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import com.codepilot1c.cli.platform.HostSystem;

/** Non-secret CLI configuration resolved from properties and environment. */
public final class CliConfiguration {
    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765";

    private final HostSystem host;

    public CliConfiguration(HostSystem host) { this.host = host; }

    public String endpointValue() {
        return first(host.systemProperty("codepilot.endpoint"), host.environment("CODEPILOT_ENDPOINT"), DEFAULT_ENDPOINT);
    }

    public URI endpoint() throws URISyntaxException {
        URI uri;
        try {
            uri = new URI(endpointValue());
        } catch (URISyntaxException exception) {
            throw invalidEndpoint();
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw invalidEndpoint();
        }
        return uri;
    }

    public Optional<String> explicitConfigPath() {
        String path = first(host.systemProperty("codepilot.config"), host.environment("CODEPILOT_CONFIG"), null);
        return Optional.ofNullable(path);
    }

    private static String first(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return fallback;
    }

    private static URISyntaxException invalidEndpoint() {
        return new URISyntaxException("", "endpoint must be an absolute HTTP(S) URI without credentials");
    }
}
