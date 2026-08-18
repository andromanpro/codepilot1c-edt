/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Secure builder/loader for standalone host settings. The same object supports
 * test-injected property and environment maps. Credentials are deliberately
 * accepted only through a protected file path, never an inline override.
 */
public final class RuntimeConfigurationLoader implements AutoCloseable {
    private static final URI DEFAULT_PROVIDER_ENDPOINT = URI.create("https://api.openai.com/v1"); //$NON-NLS-1$
    private static final String DEFAULT_PROVIDER_MODEL = "gpt-4o-mini"; //$NON-NLS-1$
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 30_000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000;
    private static final int DEFAULT_MAX_STEPS = 16;
    private static final int DEFAULT_AGENT_TIMEOUT_MILLIS = 300_000;
    private static final int MAX_TIMEOUT_MILLIS = 3_600_000;
    private static final int MAX_MAX_STEPS = 128;

    private final Map<String, String> systemProperties = new LinkedHashMap<>();
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final Map<RuntimeSetting, String> explicit = new EnumMap<>(RuntimeSetting.class);
    private Path explicitConfigFile;
    private boolean closed;

    private RuntimeConfigurationLoader() {
    }

    /** Creates a loader populated from the current Java process. */
    public static RuntimeConfigurationLoader systemDefaults() {
        RuntimeConfigurationLoader loader = new RuntimeConfigurationLoader();
        System.getProperties().forEach((key, value) -> loader.systemProperties.put(String.valueOf(key), String.valueOf(value)));
        loader.environment.putAll(System.getenv());
        return loader;
    }

    /** Creates an empty loader for an embedding host or deterministic test. */
    public static RuntimeConfigurationLoader builder() {
        return new RuntimeConfigurationLoader();
    }

    public RuntimeConfigurationLoader systemProperties(Map<String, String> values) {
        ensureOpen();
        systemProperties.clear();
        if (values != null) systemProperties.putAll(values);
        return this;
    }

    public RuntimeConfigurationLoader environment(Map<String, String> values) {
        ensureOpen();
        environment.clear();
        if (values != null) environment.putAll(values);
        return this;
    }

    /** Supplies one highest-precedence ordinary value. Raw API-key text is not a setting. */
    public RuntimeConfigurationLoader override(RuntimeSetting setting, String value) {
        ensureOpen();
        explicit.put(Objects.requireNonNull(setting, "setting"), Objects.requireNonNull(value, "value")); //$NON-NLS-1$ //$NON-NLS-2$
        return this;
    }

    /** Supplies an explicit optional config file, taking precedence over codepilot.config. */
    public RuntimeConfigurationLoader configFile(Path file) {
        ensureOpen();
        explicitConfigFile = Objects.requireNonNull(file, "file"); //$NON-NLS-1$
        return this;
    }

    /** Resolves and validates configuration. */
    public RuntimeConfiguration load() {
        ensureOpen();
        rejectInlineSecretInputs();
        FileInput fileInput = selectConfigFile();
        Map<String, String> file = StrictPropertiesFile.read(fileInput.path(), fileInput.required(),
                fileInput.protectParent());
        validateFileKeys(file);
        EnumMap<RuntimeSetting, Candidate> candidates = candidates(file);
        URI providerBaseUri = endpoint(candidates.get(RuntimeSetting.PROVIDER_BASE_URI), RuntimeSetting.PROVIDER_BASE_URI);
        java.util.Optional<URI> mcpEndpoint = optionalEndpoint(candidates.get(RuntimeSetting.MCP_ENDPOINT));
        String model = model(candidates.get(RuntimeSetting.PROVIDER_MODEL));
        int connectMillis = boundedInteger(candidates.get(RuntimeSetting.PROVIDER_CONNECT_TIMEOUT_MILLIS), RuntimeSetting.PROVIDER_CONNECT_TIMEOUT_MILLIS, 1, MAX_TIMEOUT_MILLIS);
        int requestMillis = boundedInteger(candidates.get(RuntimeSetting.PROVIDER_REQUEST_TIMEOUT_MILLIS), RuntimeSetting.PROVIDER_REQUEST_TIMEOUT_MILLIS, 1, MAX_TIMEOUT_MILLIS);
        int maxSteps = boundedInteger(candidates.get(RuntimeSetting.AGENT_MAX_STEPS), RuntimeSetting.AGENT_MAX_STEPS, 1, MAX_MAX_STEPS);
        int agentMillis = boundedInteger(candidates.get(RuntimeSetting.AGENT_TIMEOUT_MILLIS), RuntimeSetting.AGENT_TIMEOUT_MILLIS, 1, MAX_TIMEOUT_MILLIS);
        char[] apiKey = resolveApiKey(candidates.get(RuntimeSetting.PROVIDER_API_KEY_FILE), fileInput.path());
        try {
            return new RuntimeConfiguration(
                        resolved(providerBaseUri, candidates.get(RuntimeSetting.PROVIDER_BASE_URI)),
                        resolved(model, candidates.get(RuntimeSetting.PROVIDER_MODEL)),
                        resolved(Duration.ofMillis(connectMillis), candidates.get(RuntimeSetting.PROVIDER_CONNECT_TIMEOUT_MILLIS)),
                        resolved(Duration.ofMillis(requestMillis), candidates.get(RuntimeSetting.PROVIDER_REQUEST_TIMEOUT_MILLIS)),
                        resolved(mcpEndpoint, candidates.get(RuntimeSetting.MCP_ENDPOINT)),
                        resolved(maxSteps, candidates.get(RuntimeSetting.AGENT_MAX_STEPS)),
                        resolved(Duration.ofMillis(agentMillis), candidates.get(RuntimeSetting.AGENT_TIMEOUT_MILLIS)),
                    sources(candidates), apiKey);
        } finally {
            Arrays.fill(apiKey, '\0');
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private FileInput selectConfigFile() {
        if (explicitConfigFile != null) return new FileInput(explicitConfigFile, true, false);
        String property = systemProperties.get("codepilot.config"); //$NON-NLS-1$
        if (nonBlank(property)) return new FileInput(path(property, "config"), true, false); //$NON-NLS-1$
        String env = environment.get("CODEPILOT_CONFIG"); //$NON-NLS-1$
        if (nonBlank(env)) return new FileInput(path(env, "config"), true, false); //$NON-NLS-1$
        return new FileInput(PortableConfigPath.systemDefault(), false, true);
    }

    private EnumMap<RuntimeSetting, Candidate> candidates(Map<String, String> file) {
        EnumMap<RuntimeSetting, Candidate> all = new EnumMap<>(RuntimeSetting.class);
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            String defaultValue = defaultValue(setting);
            Candidate candidate = new Candidate(defaultValue, ConfigurationSource.DEFAULT);
            if (file.containsKey(setting.key())) candidate = new Candidate(file.get(setting.key()), ConfigurationSource.CONFIG_FILE);
            String env = environment.get(setting.environmentName());
            if (nonBlank(env)) candidate = new Candidate(env, ConfigurationSource.ENVIRONMENT);
            String property = systemProperties.get(setting.propertyName());
            if (nonBlank(property)) candidate = new Candidate(property, ConfigurationSource.SYSTEM_PROPERTY);
            if (explicit.containsKey(setting)) candidate = new Candidate(explicit.get(setting), ConfigurationSource.EXPLICIT);
            all.put(setting, candidate);
        }
        return all;
    }

    private char[] resolveApiKey(Candidate keyFile, Path configFile) {
        if (keyFile.value() == null || keyFile.value().isBlank()) return new char[0];
        Path file = path(keyFile.value(), RuntimeSetting.PROVIDER_API_KEY_FILE.key());
        if (!file.isAbsolute() && keyFile.source() == ConfigurationSource.CONFIG_FILE) {
            Path parent = configFile.toAbsolutePath().normalize().getParent();
            file = parent == null ? file.toAbsolutePath().normalize() : parent.resolve(file).normalize();
        }
        return readSecret(file);
    }

    private char[] readSecret(Path file) {
        byte[] bytes = StrictPropertiesFile.readSecret(file);
        try {
            char[] result = StrictPropertiesFile.decodeUtf8(bytes, ConfigurationErrorCode.SECRET_UNAVAILABLE,
                    RuntimeSetting.PROVIDER_API_KEY_FILE.key());
            result = trimTrailingLineBreaks(result);
            for (char character : result) {
                if (character == '\0' || character == '\n' || character == '\r') {
                    Arrays.fill(result, '\0');
                    throw StrictPropertiesFile.error(ConfigurationErrorCode.SECRET_UNAVAILABLE,
                            RuntimeSetting.PROVIDER_API_KEY_FILE.key(), "file contains an unsupported character"); //$NON-NLS-1$
                }
            }
            return result;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static char[] trimTrailingLineBreaks(char[] chars) {
        int index = chars.length;
        while (index > 0 && (chars[index - 1] == '\n' || chars[index - 1] == '\r')) chars[--index] = '\0';
        if (index == chars.length) return chars;
        char[] trimmed = Arrays.copyOf(chars, index);
        Arrays.fill(chars, '\0');
        return trimmed;
    }

    private static <T> ResolvedSetting<T> resolved(T value, Candidate candidate) {
        return new ResolvedSetting<>(value, candidate.source());
    }

    private static Map<RuntimeSetting, ConfigurationSource> sources(Map<RuntimeSetting, Candidate> candidates) {
        EnumMap<RuntimeSetting, ConfigurationSource> result = new EnumMap<>(RuntimeSetting.class);
        candidates.forEach((setting, candidate) -> result.put(setting, candidate.source()));
        return result;
    }

    private static java.util.Optional<URI> optionalEndpoint(Candidate candidate) {
        return candidate.value() == null || candidate.value().isBlank()
                ? java.util.Optional.empty() : java.util.Optional.of(endpoint(candidate, RuntimeSetting.MCP_ENDPOINT));
    }

    private static URI endpoint(Candidate candidate, RuntimeSetting setting) {
        try {
            URI uri = new URI(candidate.value()).normalize();
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw invalidEndpoint(setting, "must be an absolute URI without user info, query, or fragment"); //$NON-NLS-1$
            }
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) throw invalidEndpoint(setting, "must use HTTP or HTTPS"); //$NON-NLS-1$ //$NON-NLS-2$
            if ("http".equals(scheme) && !isLoopback(uri.getHost())) throw invalidEndpoint(setting, "insecure HTTP is limited to loopback hosts"); //$NON-NLS-1$ //$NON-NLS-2$
            String text = uri.toString();
            while (text.endsWith("/")) text = text.substring(0, text.length() - 1); //$NON-NLS-1$
            return URI.create(text);
        } catch (URISyntaxException exception) {
            throw invalidEndpoint(setting, "is not a valid URI"); //$NON-NLS-1$
        }
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String model(Candidate candidate) {
        String value = candidate.value();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) { //$NON-NLS-1$
            throw error(ConfigurationErrorCode.INVALID_VALUE, RuntimeSetting.PROVIDER_MODEL, "must be a 1-128 character model identifier"); //$NON-NLS-1$
        }
        return value;
    }

    private static int boundedInteger(Candidate candidate, RuntimeSetting setting, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(candidate.value());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw error(ConfigurationErrorCode.INVALID_VALUE, setting,
                    "must be an integer from " + minimum + " to " + maximum); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static ConfigurationException invalidEndpoint(RuntimeSetting setting, String detail) {
        return error(ConfigurationErrorCode.INVALID_ENDPOINT, setting, detail);
    }

    private static ConfigurationException error(ConfigurationErrorCode code, RuntimeSetting setting, String detail) {
        return StrictPropertiesFile.error(code, setting.key(), detail);
    }

    private static Path path(String value, String setting) {
        try {
            return Path.of(value);
        } catch (RuntimeException exception) {
            throw StrictPropertiesFile.error(ConfigurationErrorCode.INVALID_VALUE, setting, "is not a valid path"); //$NON-NLS-1$
        }
    }

    private static String defaultValue(RuntimeSetting setting) {
        return switch (setting) {
        case PROVIDER_BASE_URI -> DEFAULT_PROVIDER_ENDPOINT.toString();
        case PROVIDER_MODEL -> DEFAULT_PROVIDER_MODEL;
        case PROVIDER_CONNECT_TIMEOUT_MILLIS -> String.valueOf(DEFAULT_CONNECT_TIMEOUT_MILLIS);
        case PROVIDER_REQUEST_TIMEOUT_MILLIS -> String.valueOf(DEFAULT_REQUEST_TIMEOUT_MILLIS);
        case MCP_ENDPOINT, PROVIDER_API_KEY_FILE -> null;
        case AGENT_MAX_STEPS -> String.valueOf(DEFAULT_MAX_STEPS);
        case AGENT_TIMEOUT_MILLIS -> String.valueOf(DEFAULT_AGENT_TIMEOUT_MILLIS);
        };
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }

    private void validateFileKeys(Map<String, String> file) {
        for (String key : file.keySet()) {
            if (StrictPropertiesFile.looksLikeSecretKey(key)) {
                throw StrictPropertiesFile.error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "inline secret key is forbidden"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            boolean known = false;
            for (RuntimeSetting setting : RuntimeSetting.values()) known |= setting.key().equals(key);
            if (!known) throw StrictPropertiesFile.error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "unknown key"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void rejectInlineSecretInputs() {
        for (String key : systemProperties.keySet()) {
            if (hasInlineSecretAlias(key, "codepilot")) { //$NON-NLS-1$
                throw StrictPropertiesFile.error(ConfigurationErrorCode.INVALID_VALUE, "config", "inline secret property is forbidden"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (String key : environment.keySet()) {
            if (hasInlineSecretAlias(key, "codepilot")) { //$NON-NLS-1$
                throw StrictPropertiesFile.error(ConfigurationErrorCode.INVALID_VALUE, "config", "inline secret environment value is forbidden"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private static boolean hasInlineSecretAlias(String key, String prefix) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        String normalizedPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith(normalizedPrefix) || key.length() <= prefix.length()) return false;
        char separator = key.charAt(prefix.length());
        if (separator != '.' && separator != '_' && separator != '-') return false;
        return StrictPropertiesFile.looksLikeSecretKey(key.substring(prefix.length() + 1));
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("RuntimeConfigurationLoader is closed"); //$NON-NLS-1$
    }

    private record Candidate(String value, ConfigurationSource source) { }
    private record FileInput(Path path, boolean required, boolean protectParent) { }
}
