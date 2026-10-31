package com.codepilot1c.core.mcp.host.discovery;

/** Builds a local, connectable discovery URL without changing listener bind semantics. */
public final class McpHostInstanceEndpoint {

    private McpHostInstanceEndpoint() {
    }

    public static String localBaseUrl(String bindAddress, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Bound HTTP port is invalid"); //$NON-NLS-1$
        }
        String address = bindAddress == null ? "" : bindAddress.trim(); //$NON-NLS-1$
        if (address.isEmpty() || "0.0.0.0".equals(address)) { //$NON-NLS-1$
            address = "127.0.0.1"; //$NON-NLS-1$
        } else if ("::".equals(address) || "[::]".equals(address)) { //$NON-NLS-1$ //$NON-NLS-2$
            address = "::1"; //$NON-NLS-1$
        } else if (address.startsWith("[") && address.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
            address = address.substring(1, address.length() - 1);
        }
        String host = address.indexOf(':') >= 0 ? "[" + address + "]" : address; //$NON-NLS-1$ //$NON-NLS-2$
        return "http://" + host + ":" + port; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
