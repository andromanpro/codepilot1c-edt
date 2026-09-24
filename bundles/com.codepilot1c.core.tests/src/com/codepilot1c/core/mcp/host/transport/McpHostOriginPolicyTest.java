package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sun.net.httpserver.Headers;

public class McpHostOriginPolicyTest {

    private final McpHostOriginPolicy local = new McpHostOriginPolicy("127.0.0.1"); //$NON-NLS-1$

    @Test
    public void nativeClientsWithoutOriginRemainAllowed() {
        assertTrue(local.allows(new Headers()));
    }

    @Test
    public void loopbackBrowserOriginsAreAllowed() {
        for (String origin : new String[] {
                "http://127.0.0.1:8765", "http://localhost:3000", //$NON-NLS-1$ //$NON-NLS-2$
                "http://[::1]:8765", "http://127.0.0.2"}) { //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(origin, local.allows(headers(origin)));
        }
    }

    @Test
    public void remoteAndMalformedOriginsAreRejected() {
        for (String origin : new String[] {
                "http://evil.example:8765", "http://localhost.evil.example", //$NON-NLS-1$ //$NON-NLS-2$
                "http://localhost@evil.example", "https://localhost:8765", //$NON-NLS-1$ //$NON-NLS-2$
                "null", "http://localhost:8765/path", "http://127.0.0.999", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:99999", "http://localhost:"}) { //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(origin, local.allows(headers(origin)));
        }
        Headers duplicates = headers("http://localhost:8765"); //$NON-NLS-1$
        duplicates.add("Origin", "http://evil.example"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(local.allows(duplicates));
    }

    @Test
    public void remoteBindAcceptsOnlyItsConfiguredBrowserOrigin() {
        McpHostOriginPolicy remote = new McpHostOriginPolicy("edt.example.com"); //$NON-NLS-1$
        assertTrue(remote.allows(new Headers()));
        assertTrue(remote.allows(headers("http://edt.example.com:8765"))); //$NON-NLS-1$
        assertFalse(remote.allows(headers("http://evil.example:8765"))); //$NON-NLS-1$
        assertFalse(new McpHostOriginPolicy("0.0.0.0") //$NON-NLS-1$
                .allows(headers("http://evil.example:8765"))); //$NON-NLS-1$
    }

    private static Headers headers(String origin) {
        Headers headers = new Headers();
        headers.add("Origin", origin); //$NON-NLS-1$
        return headers;
    }
}
