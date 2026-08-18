package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.discovery.McpHostInstanceRegistryPublisher;

/** Verifies that discovery is attached to the actual HTTP host lifecycle, not its configuration. */
public class McpHostServerInstanceDiscoveryTest {

    @Test
    public void publishesOnlyAfterHttpBindAndCleansUpOnStop() throws Exception {
        Path directory = Files.createTempDirectory("codepilot-host-discovery"); //$NON-NLS-1$
        String instanceId = "11111111-2222-4333-8444-555555555555"; //$NON-NLS-1$
        String oldId = System.getProperty(McpHostInstanceRegistryPublisher.INSTANCE_ID_PROPERTY);
        String oldOwner = System.getProperty(McpHostInstanceRegistryPublisher.OWNER_PROPERTY);
        String oldDirectory = System.getProperty(McpHostInstanceRegistryPublisher.REGISTRY_DIRECTORY_PROPERTY);
        McpHostServer server = null;
        Path file = directory.resolve(instanceId + ".json"); //$NON-NLS-1$
        try {
            System.setProperty(McpHostInstanceRegistryPublisher.INSTANCE_ID_PROPERTY, instanceId);
            System.setProperty(McpHostInstanceRegistryPublisher.OWNER_PROPERTY, "cli"); //$NON-NLS-1$
            System.setProperty(McpHostInstanceRegistryPublisher.REGISTRY_DIRECTORY_PROPERTY, directory.toString());
            McpHostConfig config = McpHostConfig.defaults();
            config.setEnabled(true);
            config.setHttpEnabled(true);
            config.setBindAddress("0.0.0.0"); //$NON-NLS-1$
            config.setPort(0);
            config.setAuthMode(McpHostConfig.AuthMode.NONE);
            server = new McpHostServer(config);
            server.start();

            assertTrue(server.isRunning());
            assertTrue(Files.isRegularFile(file));
            String json = Files.readString(file);
            assertTrue(json.contains("\"owner\":\"cli\"")); //$NON-NLS-1$
            assertTrue(json.contains("\"baseUrl\":\"http://127.0.0.1:")); //$NON-NLS-1$
            assertFalse(json.contains("\"port\":0")); //$NON-NLS-1$
            assertTrue(json.contains("\"llmBrokerVersion\":1")); //$NON-NLS-1$
            assertFalse(json.contains("\"capabilities\"")); //$NON-NLS-1$

            server.stop();
            assertFalse(Files.exists(file));
        } finally {
            if (server != null && server.isRunning()) {
                server.stop();
            }
            restore(McpHostInstanceRegistryPublisher.INSTANCE_ID_PROPERTY, oldId);
            restore(McpHostInstanceRegistryPublisher.OWNER_PROPERTY, oldOwner);
            restore(McpHostInstanceRegistryPublisher.REGISTRY_DIRECTORY_PROPERTY, oldDirectory);
            deleteTree(directory);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
    }
}
