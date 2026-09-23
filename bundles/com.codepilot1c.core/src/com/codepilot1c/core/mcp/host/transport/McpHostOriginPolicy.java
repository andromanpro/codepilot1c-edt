package com.codepilot1c.core.mcp.host.transport;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

import com.sun.net.httpserver.Headers;

/** Validates browser Origins at the MCP HTTP endpoint without requiring one from native clients. */
final class McpHostOriginPolicy {

    private final String bindAddress;
    private final boolean loopbackBind;

    McpHostOriginPolicy(String bindAddress) {
        this.bindAddress = bindAddress;
        this.loopbackBind = isLoopbackBind(bindAddress);
    }

    boolean allows(Headers headers) {
        List<String> values = headers.get("Origin"); //$NON-NLS-1$
        if (values == null || values.isEmpty()) {
            return true;
        }
        if (values.size() != 1) {
            return false;
        }
        String value = values.get(0);
        if (value == null) {
            return false;
        }
        try {
            URI origin = new URI(value);
            String host = origin.getHost();
            if (!"http".equalsIgnoreCase(origin.getScheme()) //$NON-NLS-1$
                    || host == null || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || origin.getPort() > 65535
                    || origin.getRawAuthority().endsWith(":") //$NON-NLS-1$
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())) {
                return false;
            }
            if (loopbackBind) {
                return isLoopbackOriginHost(host);
            }
            return bindAddress != null && !bindAddress.isBlank()
                    && !"0.0.0.0".equals(bindAddress) //$NON-NLS-1$
                    && !"::".equals(bindAddress) //$NON-NLS-1$
                    && host.equalsIgnoreCase(bindAddress);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isLoopbackBind(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isLoopbackOriginHost(String host) {
        if ("localhost".equalsIgnoreCase(host) //$NON-NLS-1$
                || "[::1]".equalsIgnoreCase(host) //$NON-NLS-1$
                || "::1".equalsIgnoreCase(host)) { //$NON-NLS-1$
            return true;
        }
        String[] octets = host.split("\\.", -1); //$NON-NLS-1$
        if (octets.length != 4 || !"127".equals(octets[0])) { //$NON-NLS-1$
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                if (octet.charAt(i) < '0' || octet.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
