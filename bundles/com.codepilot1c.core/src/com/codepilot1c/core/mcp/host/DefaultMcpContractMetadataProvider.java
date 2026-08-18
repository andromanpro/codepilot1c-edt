package com.codepilot1c.core.mcp.host;

import java.util.Optional;
import java.util.function.Supplier;

import com.codepilot1c.core.internal.VibeCorePlugin;

/**
 * Default production provider for the CodePilot MCP contract metadata.
 */
public final class DefaultMcpContractMetadataProvider implements McpContractMetadataProvider {

    private static final int CONTRACT_VERSION = 1;

    private final McpRuntimeInfoGateway runtimeGateway;
    private final Supplier<String> pluginVersionSupplier;

    public DefaultMcpContractMetadataProvider() {
        this(new DefaultMcpRuntimeInfoGateway(), DefaultMcpContractMetadataProvider::resolvePluginVersion);
    }

    public DefaultMcpContractMetadataProvider(
            McpRuntimeInfoGateway runtimeGateway,
            Supplier<String> pluginVersionSupplier) {
        this.runtimeGateway = runtimeGateway != null ? runtimeGateway : new DefaultMcpRuntimeInfoGateway();
        this.pluginVersionSupplier = pluginVersionSupplier != null
            ? pluginVersionSupplier
            : DefaultMcpContractMetadataProvider::resolvePluginVersion;
    }

    @Override
    public McpContractMetadata snapshot() {
        return new McpContractMetadata(
            CONTRACT_VERSION,
            pluginVersionSupplier.get(),
            optionalValue(runtimeGateway.edtVersion()),
            runtimeGateway.mode(),
            optionalValue(runtimeGateway.workspace()),
            runtimeGateway.readiness());
    }

    private static String optionalValue(Optional<String> value) {
        return value != null && value.isPresent() ? value.get() : "unknown"; //$NON-NLS-1$
    }

    private static String resolvePluginVersion() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null && plugin.getBundle() != null && plugin.getBundle().getVersion() != null) {
            return plugin.getBundle().getVersion().toString();
        }
        return "unknown"; //$NON-NLS-1$
    }
}
