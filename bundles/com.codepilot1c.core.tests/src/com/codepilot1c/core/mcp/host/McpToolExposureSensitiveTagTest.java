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

    private static DefaultMcpToolExposurePolicy policy(String filter) {
        McpHostConfig config = new McpHostConfig();
        config.setExposedToolsFilter(filter);
        return new DefaultMcpToolExposurePolicy(config,
                "get_infobase_credentials"::equals, //$NON-NLS-1$
                "java_compile_probe"::equals); //$NON-NLS-1$
    }
}
