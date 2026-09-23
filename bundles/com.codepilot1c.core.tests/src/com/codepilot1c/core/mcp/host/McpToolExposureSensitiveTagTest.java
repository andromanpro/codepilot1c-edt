package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class McpToolExposureSensitiveTagTest {

    @Test
    public void wildcardDoesNotExposeSensitiveTool() {
        assertFalse(policy("*").isExposed("get_infobase_credentials")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void wildcardStillExposesNonSensitiveTool() {
        assertTrue(policy("*").isExposed("read_file")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void explicitNameExposesSensitiveTool() {
        assertTrue(policy("get_infobase_credentials").isExposed("get_infobase_credentials")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void explicitDenyStillOverridesWildcard() {
        assertFalse(policy("*,-read_file").isExposed("read_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(policy("*,-read_file").isExposed("write_file")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void wildcardDoesNotExposeLocalExecTool() {
        assertFalse(policy("*").isExposed("java_compile_probe")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void explicitNameExposesLocalExecTool() {
        assertTrue(policy("java_compile_probe").isExposed("java_compile_probe")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void localAllowWildcardExposesSensitiveAndLocalExecTools() {
        for (String loopback : new String[] {"127.0.0.1", "::1", "localhost"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            DefaultMcpToolExposurePolicy policy = policy("*", //$NON-NLS-1$
                    McpHostConfig.MutationPolicy.ALLOW, loopback);
            assertTrue(loopback, policy.isExposed("get_infobase_credentials")); //$NON-NLS-1$
            assertTrue(loopback, policy.isExposed("java_compile_probe")); //$NON-NLS-1$
            assertFalse(loopback, policy("*,-java_compile_probe", //$NON-NLS-1$
                    McpHostConfig.MutationPolicy.ALLOW, loopback)
                    .isExposed("java_compile_probe")); //$NON-NLS-1$
        }
    }

    @Test
    public void wildcardKeepsSensitiveToolsHiddenWithoutLocalAllow() {
        for (McpHostConfig.MutationPolicy decision : new McpHostConfig.MutationPolicy[] {
                McpHostConfig.MutationPolicy.DENY, McpHostConfig.MutationPolicy.ASK}) {
            assertFalse(policy("*", decision, "127.0.0.1") //$NON-NLS-1$ //$NON-NLS-2$
                    .isExposed("get_infobase_credentials")); //$NON-NLS-1$
            assertFalse(policy("*", decision, "127.0.0.1") //$NON-NLS-1$ //$NON-NLS-2$
                    .isExposed("java_compile_probe")); //$NON-NLS-1$
        }
        assertFalse(policy("*", McpHostConfig.MutationPolicy.ALLOW, "0.0.0.0") //$NON-NLS-1$ //$NON-NLS-2$
                .isExposed("get_infobase_credentials")); //$NON-NLS-1$
        assertFalse(policy("*", McpHostConfig.MutationPolicy.ALLOW, "0.0.0.0") //$NON-NLS-1$ //$NON-NLS-2$
                .isExposed("java_compile_probe")); //$NON-NLS-1$
    }

    private static DefaultMcpToolExposurePolicy policy(String filter) {
        return policy(filter, null, null);
    }

    private static DefaultMcpToolExposurePolicy policy(
            String filter, McpHostConfig.MutationPolicy decision, String bindAddress) {
        McpHostConfig config = new McpHostConfig();
        config.setExposedToolsFilter(filter);
        config.setMutationPolicy(decision);
        config.setBindAddress(bindAddress);
        return new DefaultMcpToolExposurePolicy(config,
                "get_infobase_credentials"::equals, //$NON-NLS-1$
                "java_compile_probe"::equals); //$NON-NLS-1$
    }
}
