package com.codepilot1c.core.mcp.host;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.prompt.PromptTemplateProvider;
import com.codepilot1c.core.mcp.host.resource.DiagnosticsResourceProvider;
import com.codepilot1c.core.mcp.host.resource.IMcpResourceProvider;
import com.codepilot1c.core.mcp.host.resource.StateResourceProvider;
import com.codepilot1c.core.mcp.host.resource.WorkspaceResourceProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.host.discovery.McpHostInstanceRegistryPublisher;
import com.codepilot1c.core.mcp.host.discovery.McpHostInstanceEndpoint;
import com.codepilot1c.core.mcp.host.llm.McpHostLlmBroker;
import com.codepilot1c.core.mcp.host.transport.McpHostHttpTransport;
import com.codepilot1c.core.mcp.host.transport.McpHostOAuthService;

public class McpHostServer implements IMcpHostServer {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostServer.class);

    private McpHostConfig config;
    private McpHostHttpTransport httpTransport;
    private McpHostRequestRouter router;
    private McpHostInstanceRegistryPublisher instanceRegistryPublisher;
    private volatile boolean running;

    public McpHostServer(McpHostConfig config) {
        this.config = config;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!config.isEnabled()) {
            LOG.info("MCP host is disabled"); //$NON-NLS-1$
            return;
        }

        DefaultMcpToolExposurePolicy exposurePolicy = new DefaultMcpToolExposurePolicy(config);
        List<IMcpResourceProvider> resourceProviders = List.of(
            new WorkspaceResourceProvider(),
            new DiagnosticsResourceProvider(),
            new StateResourceProvider()
        );
        router = new McpHostRequestRouter(
            exposurePolicy,
            resourceProviders,
            new PromptTemplateProvider(),
            config.getMutationPolicy(),
            config.getSessionProfileId()
        );

        if (config.isHttpEnabled()) {
            String bearerToken = config.getAuthMode() == McpHostConfig.AuthMode.OAUTH_ONLY
                    ? "" //$NON-NLS-1$
                    : config.getBearerToken();
            McpHostOAuthService oauthService = new McpHostOAuthService(
                config.getBindAddress(),
                config.getPort(),
                bearerToken
            );
            httpTransport = new McpHostHttpTransport(
                config.getBindAddress(),
                config.getPort(),
                oauthService,
                router,
                config.getAuthMode(),
                Duration.ofSeconds(config.getSessionIdleTimeoutSeconds()),
                new McpHostLlmBroker(config.isLlmEnabled())
            );
            httpTransport.start();
            publishInstanceAfterBind();
        }

        running = true;
        String sessionProfileId = config.getSessionProfileId();
        boolean profileGateEnabled = sessionProfileId != null && !sessionProfileId.isBlank();
        LOG.info("MCP host server started (http=%s, auth=%s, sessionProfile=%s, profileGate=%s)", //$NON-NLS-1$
            Boolean.valueOf(config.isHttpEnabled()), config.getAuthMode(),
            profileGateEnabled ? sessionProfileId : "<legacy>", //$NON-NLS-1$
            Boolean.valueOf(profileGateEnabled));
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        try {
            if (httpTransport != null) {
                httpTransport.stop();
                httpTransport = null;
            }
        } finally {
            unpublishInstance();
        }
        running = false;
        LOG.info("MCP host server stopped"); //$NON-NLS-1$
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void reloadConfig() {
        this.config = McpHostConfigStore.getInstance().load();
        stop();
        start();
    }

    @Override
    public Map<String, Object> getCapabilities() {
        if (router == null) {
            return Map.of();
        }
        return router.capabilitiesSnapshot();
    }

    @Override
    public List<McpHostSession> getSessions() {
        if (httpTransport == null) {
            return List.of();
        }
        return List.copyOf(httpTransport.getSessionsSnapshot());
    }

    private void publishInstanceAfterBind() {
        String opId = java.util.UUID.randomUUID().toString();
        try {
            McpHostInstanceRegistryPublisher publisher = McpHostInstanceRegistryPublisher.fromSystemProperties();
            McpContractMetadata metadata = new DefaultMcpContractMetadataProvider().snapshot();
            int boundPort = httpTransport.getBoundPort();
            boolean written = publisher.publish(boundPort,
                    McpHostInstanceEndpoint.localBaseUrl(config.getBindAddress(), boundPort),
                    metadata.workspace(), edtHome(), metadata.mode(), metadata.pluginVersion(),
                    config.getAuthMode().name(), config.isLlmEnabled());
            if (written) {
                instanceRegistryPublisher = publisher;
                LOG.info("[opId=%s] MCP host instance registry published", opId); //$NON-NLS-1$
            } else {
                LOG.warn("[opId=%s] MCP host instance registry publish failed", opId); //$NON-NLS-1$
            }
        } catch (RuntimeException e) {
            // Discovery is additive: a malformed supervisor setting or an unavailable home
            // directory must never prevent the already-bound MCP host from serving requests.
            LOG.warn("[opId=%s] MCP host instance registry unavailable: %s", opId, //$NON-NLS-1$
                    e.getMessage());
        }
    }

    private void unpublishInstance() {
        McpHostInstanceRegistryPublisher publisher = instanceRegistryPublisher;
        instanceRegistryPublisher = null;
        if (publisher == null) {
            return;
        }
        String opId = java.util.UUID.randomUUID().toString();
        if (publisher.unpublish()) {
            LOG.info("[opId=%s] MCP host instance registry removed", opId); //$NON-NLS-1$
        } else {
            LOG.warn("[opId=%s] MCP host instance registry cleanup skipped or failed", opId); //$NON-NLS-1$
        }
    }

    private static String edtHome() {
        String configured = System.getProperty("edt.home"); //$NON-NLS-1$
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String installArea = System.getProperty("osgi.install.area"); //$NON-NLS-1$
        return installArea == null || installArea.isBlank() ? "unknown" : installArea; //$NON-NLS-1$
    }
}
