package com.codepilot1c.runtime.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable configuration for a standalone MCP HTTP client. */
public final class McpClientConfig implements AutoCloseable {
    public static final List<String> SUPPORTED_PROTOCOLS = List.of(
            "2025-11-25", "2025-06-18", "2024-11-05");

    private final URI endpoint;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final List<String> protocolPreferences;
    private final boolean allowInsecureHttp;
    private char[] bearerToken;

    private McpClientConfig(Builder builder) {
        this.endpoint = validateEndpoint(builder.endpoint, builder.allowInsecureHttp);
        this.connectTimeout = validateDuration(builder.connectTimeout, "connectTimeout");
        this.requestTimeout = validateDuration(builder.requestTimeout, "requestTimeout");
        this.protocolPreferences = validateProtocols(builder.protocolPreferences);
        this.allowInsecureHttp = builder.allowInsecureHttp;
        this.bearerToken = builder.bearerToken == null ? null : builder.bearerToken.clone();
    }

    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    public static Builder builder(String endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        try {
            return builder(URI.create(endpoint));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid MCP endpoint URI", e);
        }
    }

    public URI endpoint() { return endpoint; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration requestTimeout() { return requestTimeout; }
    public List<String> protocolPreferences() { return protocolPreferences; }
    public boolean allowInsecureHttp() { return allowInsecureHttp; }

    /** Returns a short-lived copy for an HTTP request. The caller must wipe it. */
    char[] copyBearerToken() {
        synchronized (this) {
            return bearerToken == null ? null : bearerToken.clone();
        }
    }

    boolean hasBearerToken() {
        synchronized (this) {
            return bearerToken != null && bearerToken.length > 0;
        }
    }

    @Override
    public synchronized void close() {
        wipe(bearerToken);
        bearerToken = null;
    }

    @Override
    public synchronized String toString() {
        return "McpClientConfig{endpoint=" + endpoint
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", protocolPreferences=" + protocolPreferences
                + ", allowInsecureHttp=" + allowInsecureHttp
                + ", bearerToken=<redacted>}";
    }

    static void wipe(char[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, '\0');
        }
    }

    private static Duration validateDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static List<String> validateProtocols(List<String> values) {
        Objects.requireNonNull(values, "protocolPreferences");
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("protocolPreferences cannot contain blank values");
            }
            if (!SUPPORTED_PROTOCOLS.contains(value)) {
                throw new IllegalArgumentException("Unsupported MCP protocol version: " + value);
            }
            if (!distinct.add(value)) {
                throw new IllegalArgumentException("Duplicate MCP protocol version: " + value);
            }
        }
        if (distinct.isEmpty()) {
            distinct.addAll(SUPPORTED_PROTOCOLS);
        }
        return List.copyOf(distinct);
    }

    private static URI validateEndpoint(URI value, boolean allowInsecureHttp) {
        Objects.requireNonNull(value, "endpoint");
        if (value.getUserInfo() != null) {
            throw new IllegalArgumentException("MCP endpoint must not contain URI user information");
        }
        String scheme = value.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("MCP endpoint scheme must be http or https");
        }
        if (value.getHost() == null || value.getHost().isBlank()) {
            throw new IllegalArgumentException("MCP endpoint must contain a host");
        }
        if ("http".equalsIgnoreCase(scheme) && !allowInsecureHttp && !isLoopback(value.getHost())) {
            throw new IllegalArgumentException("Plain HTTP is allowed only for loopback endpoints by default");
        }
        return value;
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("localhost".equals(normalized) || "ip6-localhost".equals(normalized)) {
            return true;
        }
        String[] ipv4 = normalized.split("\\.", -1);
        if (ipv4.length == 4) {
            try {
                return Integer.parseInt(ipv4[0]) == 127
                        && Integer.parseInt(ipv4[1]) >= 0 && Integer.parseInt(ipv4[1]) <= 255
                        && Integer.parseInt(ipv4[2]) >= 0 && Integer.parseInt(ipv4[2]) <= 255
                        && Integer.parseInt(ipv4[3]) >= 0 && Integer.parseInt(ipv4[3]) <= 255;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if ("::1".equals(normalized)) return true;
        String[] ipv6 = normalized.split(":", -1);
        if (ipv6.length == 8 && "1".equals(ipv6[7])) {
            for (int index = 0; index < 7; index++) {
                if (!ipv6[index].matches("0{1,4}")) return false;
            }
            return true;
        }
        return false;
    }

    public static final class Builder {
        private URI endpoint;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private List<String> protocolPreferences = new ArrayList<>(SUPPORTED_PROTOCOLS);
        private boolean allowInsecureHttp;
        private char[] bearerToken;

        private Builder(URI endpoint) { this.endpoint = endpoint; }

        public Builder connectTimeout(Duration value) { this.connectTimeout = value; return this; }
        public Builder requestTimeout(Duration value) { this.requestTimeout = value; return this; }
        public Builder protocolPreferences(List<String> value) {
            this.protocolPreferences = value == null ? null : new ArrayList<>(value);
            return this;
        }
        public Builder allowInsecureHttp(boolean value) { this.allowInsecureHttp = value; return this; }
        public Builder bearerToken(char[] value) {
            McpClientConfig.wipe(this.bearerToken);
            this.bearerToken = value == null ? null : value.clone();
            return this;
        }
        public McpClientConfig build() {
            try {
                return new McpClientConfig(this);
            } finally {
                McpClientConfig.wipe(bearerToken);
                bearerToken = null;
            }
        }
    }
}
