package com.codepilot1c.core.mcp.host;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Singleton manager for MCP host lifecycle.
 */
public class McpHostManager {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostManager.class);

    private static McpHostManager instance;

    private IMcpHostServer server;

    public static synchronized McpHostManager getInstance() {
        if (instance == null) {
            instance = new McpHostManager();
        }
        return instance;
    }

    public synchronized void startIfEnabled() {
        startIfEnabledLocked();
    }

    /**
     * Starts or adopts the shared MCP host for one explicit application invocation.
     *
     * <p>The returned lease is the only application-scoped stop boundary. Closing it stops the
     * server only if it is still the same server that this invocation started or adopted; it
     * cannot stop a replacement server or call the manager-wide shutdown.</p>
     */
    public synchronized ApplicationHostLease startForApplication() {
        return new ApplicationHostLease(this, startIfEnabledLocked(true));
    }

    public synchronized void restart() {
        stopAll();
        startIfEnabled();
    }

    public synchronized void stopAll() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public synchronized IMcpHostServer getServer() {
        return server;
    }

    private IMcpHostServer startIfEnabledLocked() {
        return startIfEnabledLocked(false);
    }

    private IMcpHostServer startIfEnabledLocked(boolean forceEnabled) {
        McpHostConfig cfg = McpHostConfigStore.getInstance().load();
        if (forceEnabled) {
            // An explicit -application launch is an opt-in request for this host. Keep the
            // object in memory and do not write the user's GUI preference back to storage.
            cfg.setEnabled(true);
        }
        if (!cfg.isEnabled()) {
            LOG.info("MCP host is disabled by preference"); //$NON-NLS-1$
            return null;
        }
        if (server == null) {
            server = new McpHostServer(cfg);
        }
        try {
            server.start();
        } catch (RuntimeException e) {
            IMcpHostServer failedServer = server;
            server = null;
            try {
                failedServer.stop();
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        return server;
    }

    private synchronized void stopApplicationHost(ApplicationHostLease lease) {
        if (lease == null || lease.owner != this || lease.server == null || server != lease.server) {
            return;
        }
        server.stop();
        server = null;
    }

    /** A scoped stop handle for the host associated with one application invocation. */
    public static final class ApplicationHostLease implements AutoCloseable {

        private final McpHostManager owner;
        private final IMcpHostServer server;
        private boolean closed;

        private ApplicationHostLease(McpHostManager owner, IMcpHostServer server) {
            this.owner = owner;
            this.server = server;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.stopApplicationHost(this);
        }
    }
}
