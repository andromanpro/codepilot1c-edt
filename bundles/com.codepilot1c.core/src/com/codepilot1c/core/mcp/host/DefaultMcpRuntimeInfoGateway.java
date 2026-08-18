package com.codepilot1c.core.mcp.host;

import java.util.Optional;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/**
 * EDT-backed runtime boundary for MCP readiness and discovery metadata.
 */
public final class DefaultMcpRuntimeInfoGateway implements McpRuntimeInfoGateway {

    private static final String NOT_READY_REASON = "EDT runtime services are not ready"; //$NON-NLS-1$
    private static final String DEGRADED_REASON = "EDT runtime services failed readiness probe"; //$NON-NLS-1$

    private final EdtMetadataGateway edtGateway;
    private final McpEdtVersionSupplier edtVersionSupplier;

    public DefaultMcpRuntimeInfoGateway() {
        this(new EdtMetadataGateway(), DefaultMcpRuntimeInfoGateway::resolveEdtProductVersion);
    }

    public DefaultMcpRuntimeInfoGateway(EdtMetadataGateway edtGateway) {
        this(edtGateway, DefaultMcpRuntimeInfoGateway::resolveEdtProductVersion);
    }

    public DefaultMcpRuntimeInfoGateway(McpEdtVersionSupplier edtVersionSupplier) {
        this(new EdtMetadataGateway(), edtVersionSupplier);
    }

    public DefaultMcpRuntimeInfoGateway(
            EdtMetadataGateway edtGateway,
            McpEdtVersionSupplier edtVersionSupplier) {
        this.edtGateway = edtGateway != null ? edtGateway : new EdtMetadataGateway();
        this.edtVersionSupplier = edtVersionSupplier != null
                ? edtVersionSupplier
                : DefaultMcpRuntimeInfoGateway::resolveEdtProductVersion;
    }

    @Override
    public Optional<String> edtVersion() {
        try {
            return edtVersionSupplier.get();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> resolveEdtProductVersion() {
        try {
            IProduct product = Platform.getProduct();
            if (product != null) {
                Bundle definingBundle = product.getDefiningBundle();
                Optional<String> definingVersion = bundleVersion(definingBundle);
                if (definingVersion.isPresent()) {
                    return definingVersion;
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> bundleVersion(Bundle bundle) {
        if (bundle == null || bundle.getVersion() == null) {
            return Optional.empty();
        }
        String version = bundle.getVersion().toString();
        return version.isBlank() ? Optional.empty() : Optional.of(version);
    }

    @Override
    public Optional<String> workspace() {
        try {
            if (ResourcesPlugin.getWorkspace() == null
                    || ResourcesPlugin.getWorkspace().getRoot() == null
                    || ResourcesPlugin.getWorkspace().getRoot().getLocation() == null) {
                return Optional.empty();
            }
            return Optional.of(ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public String mode() {
        return isHeadless()
            ? "headless" //$NON-NLS-1$
            : "gui"; //$NON-NLS-1$
    }

    private boolean isHeadless() {
        return Boolean.parseBoolean(System.getProperty("codepilot1c.headless", "false")) //$NON-NLS-1$ //$NON-NLS-2$
                || "com.codepilot1c.core.headless".equals(System.getProperty("eclipse.application")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public McpReadiness readiness() {
        try {
            return edtGateway.isEdtAvailable()
                ? McpReadiness.available()
                : McpReadiness.starting(NOT_READY_REASON);
        } catch (RuntimeException e) {
            return McpReadiness.notReady(DEGRADED_REASON);
        }
    }
}
