package com.codepilot1c.core.mcp.host.discovery;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.codepilot1c.core.mcp.host.llm.McpHostLlmBroker;
import com.google.gson.Gson;

/**
 * Publishes one running HTTP MCP host instance to a local, supervisor-readable registry.
 *
 * <p>The registry deliberately contains connection metadata only. Authentication credentials,
 * headers and OAuth tokens must never be included in its JSON payload.</p>
 */
public final class McpHostInstanceRegistryPublisher implements AutoCloseable {

    public static final String INSTANCE_ID_PROPERTY = "codepilot.instance.id"; //$NON-NLS-1$
    public static final String OWNER_PROPERTY = "codepilot.instance.owner"; //$NON-NLS-1$
    public static final String REGISTRY_DIRECTORY_PROPERTY = "codepilot.instance.registryDir"; //$NON-NLS-1$

    private static final int SCHEMA_VERSION = 1;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); //$NON-NLS-1$
    private static final String UNKNOWN = "unknown"; //$NON-NLS-1$

    private final String instanceId;
    private final String owner;
    private final Path registryDirectory;
    private final Path registryFile;
    private final Clock clock;
    private final PidSource pidSource;
    private final InstanceRegistryFileSystem fileSystem;
    private final String nonce;
    private final Gson gson;
    private boolean published;

    /** Reads and validates supervisor settings from system properties. */
    public static McpHostInstanceRegistryPublisher fromSystemProperties() {
        String configuredInstanceId = System.getProperty(INSTANCE_ID_PROPERTY);
        String instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? UUID.randomUUID().toString()
                : validateInstanceId(configuredInstanceId);
        String configuredOwner = System.getProperty(OWNER_PROPERTY);
        String owner = configuredOwner == null || configuredOwner.isBlank()
                ? "external" //$NON-NLS-1$
                : validateOwner(configuredOwner);
        String configuredDirectory = System.getProperty(REGISTRY_DIRECTORY_PROPERTY);
        Path directory = configuredDirectory == null || configuredDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home", "."), ".codepilot1c", "instances") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : Paths.get(configuredDirectory);
        return new McpHostInstanceRegistryPublisher(instanceId, owner, directory, Clock.systemUTC(),
                () -> ProcessHandle.current().pid(), new NioInstanceRegistryFileSystem());
    }

    public McpHostInstanceRegistryPublisher(String instanceId, String owner, Path registryDirectory,
            Clock clock, PidSource pidSource, InstanceRegistryFileSystem fileSystem) {
        this.instanceId = validateInstanceId(instanceId);
        this.owner = validateOwner(owner);
        if (registryDirectory == null) {
            throw new IllegalArgumentException("Registry directory is required"); //$NON-NLS-1$
        }
        this.registryDirectory = registryDirectory.normalize();
        this.registryFile = this.registryDirectory.resolve(this.instanceId + ".json"); //$NON-NLS-1$
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.pidSource = pidSource != null ? pidSource : () -> ProcessHandle.current().pid();
        this.fileSystem = fileSystem != null ? fileSystem : new NioInstanceRegistryFileSystem();
        this.nonce = UUID.randomUUID().toString();
        this.gson = new Gson();
    }

    /** Publishes a snapshot after the HTTP listener has bound its actual port. */
    public synchronized boolean publish(int port, String baseUrl, String workspace, String edtHome,
            String mode, String pluginVersion, String authMode) {
        return publish(port, baseUrl, workspace, edtHome, mode, pluginVersion, authMode, false);
    }

    /**
     * Publishes a snapshot with additive, non-secret broker capability metadata.
     *
     * <p>The schema version remains 1 because capabilities are an optional additive field.</p>
     */
    public synchronized boolean publish(int port, String baseUrl, String workspace, String edtHome,
            String mode, String pluginVersion, String authMode, boolean llmBrokerAvailable) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Bound HTTP port is invalid"); //$NON-NLS-1$
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", Integer.valueOf(SCHEMA_VERSION)); //$NON-NLS-1$
        record.put("instanceId", instanceId); //$NON-NLS-1$
        record.put("pid", Long.valueOf(pidSource.currentPid())); //$NON-NLS-1$
        record.put("port", Integer.valueOf(port)); //$NON-NLS-1$
        record.put("baseUrl", required(baseUrl)); //$NON-NLS-1$
        record.put("workspace", valueOrUnknown(workspace)); //$NON-NLS-1$
        record.put("edtHome", valueOrUnknown(edtHome)); //$NON-NLS-1$
        record.put("mode", "headless".equals(mode) ? "headless" : "gui"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        record.put("owner", owner); //$NON-NLS-1$
        record.put("startedAt", Instant.now(clock).toString()); //$NON-NLS-1$
        record.put("pluginVersion", valueOrUnknown(pluginVersion)); //$NON-NLS-1$
        record.put("authMode", valueOrUnknown(authMode)); //$NON-NLS-1$
        if (llmBrokerAvailable) {
            record.put("capabilities", List.of(McpHostLlmBroker.CAPABILITY_ID)); //$NON-NLS-1$
        }
        // The nonce is an ownership proof, not an authentication value. It lets an older host
        // avoid deleting a registry file replaced by a newer process with the same instance id.
        record.put("nonce", nonce); //$NON-NLS-1$
        try {
            fileSystem.writeAtomically(registryFile, gson.toJson(record));
            published = true;
            return true;
        } catch (Exception e) {
            published = false;
            return false;
        }
    }

    /** Removes this process's file only when it still carries this publisher's ownership proof. */
    public synchronized boolean unpublish() {
        if (!published) {
            return false;
        }
        published = false;
        try {
            return fileSystem.deleteIfOwned(registryFile, instanceId, pidSource.currentPid(), nonce);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        unpublish();
    }

    public String instanceId() {
        return instanceId;
    }

    public String owner() {
        return owner;
    }

    public Path registryDirectory() {
        return registryDirectory;
    }

    public Path registryFile() {
        return registryFile;
    }

    static String validateInstanceId(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("codepilot.instance.id must be a UUID"); //$NON-NLS-1$
        }
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("codepilot.instance.id must be a UUID", e); //$NON-NLS-1$
        }
    }

    static String validateOwner(String value) {
        if (value == null) {
            throw new IllegalArgumentException("codepilot.instance.owner must be cli or external"); //$NON-NLS-1$
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!"cli".equals(normalized) && !"external".equals(normalized)) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("codepilot.instance.owner must be cli or external"); //$NON-NLS-1$
        }
        return normalized;
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required"); //$NON-NLS-1$
        }
        return value;
    }
}
