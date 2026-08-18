package com.codepilot1c.core.mcp.host.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class McpHostInstanceRegistryPublisherTest {

    private static final String INSTANCE_ID = "11111111-2222-4333-8444-555555555555"; //$NON-NLS-1$
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T08:00:00Z"), ZoneOffset.UTC); //$NON-NLS-1$

    @Test
    public void publishesGuiAndHeadlessSnapshotsWithoutCredentials() throws Exception {
        Path directory = Files.createTempDirectory("codepilot-registry-test"); //$NON-NLS-1$
        try {
            McpHostInstanceRegistryPublisher gui = publisher(INSTANCE_ID, "external", directory, 101); //$NON-NLS-1$
            assertTrue(gui.publish(8765, "http://127.0.0.1:8765", "/workspace", "/edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "gui", "1.2.3", "OAUTH_OR_BEARER")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map<String, Object> record = record(gui.registryFile());
            assertEquals(1.0d, record.get("schemaVersion")); //$NON-NLS-1$
            assertEquals(INSTANCE_ID, record.get("instanceId")); //$NON-NLS-1$
            assertEquals(101.0d, record.get("pid")); //$NON-NLS-1$
            assertEquals(8765.0d, record.get("port")); //$NON-NLS-1$
            assertEquals("http://127.0.0.1:8765", record.get("baseUrl")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("/workspace", record.get("workspace")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("/edt", record.get("edtHome")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("gui", record.get("mode")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("external", record.get("owner")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("2026-08-18T08:00:00Z", record.get("startedAt")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("1.2.3", record.get("pluginVersion")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("OAUTH_OR_BEARER", record.get("authMode")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(record.get("nonce")); //$NON-NLS-1$
            assertTrue(gui.unpublish());

            McpHostInstanceRegistryPublisher headless = publisher(INSTANCE_ID, "cli", directory, 102); //$NON-NLS-1$
            assertTrue(headless.publish(8766, "http://127.0.0.1:8766", "", null, //$NON-NLS-1$ //$NON-NLS-2$
                    "headless", "plugin", "NONE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            record = record(headless.registryFile());
            assertEquals("headless", record.get("mode")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("cli", record.get("owner")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("unknown", record.get("workspace")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("unknown", record.get("edtHome")); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    public void resolvesSupervisorOverridesAndSafeDefaults() {
        String oldId = System.getProperty(McpHostInstanceRegistryPublisher.INSTANCE_ID_PROPERTY);
        String oldOwner = System.getProperty(McpHostInstanceRegistryPublisher.OWNER_PROPERTY);
        String oldDirectory = System.getProperty(McpHostInstanceRegistryPublisher.REGISTRY_DIRECTORY_PROPERTY);
        try {
            System.setProperty(McpHostInstanceRegistryPublisher.INSTANCE_ID_PROPERTY, INSTANCE_ID);
            System.setProperty(McpHostInstanceRegistryPublisher.OWNER_PROPERTY, "cli"); //$NON-NLS-1$
            System.setProperty(McpHostInstanceRegistryPublisher.REGISTRY_DIRECTORY_PROPERTY, "/tmp/codepilot-registry"); //$NON-NLS-1$
            McpHostInstanceRegistryPublisher overridden = McpHostInstanceRegistryPublisher.fromSystemProperties();
            assertEquals(INSTANCE_ID, overridden.instanceId());
            assertEquals("cli", overridden.owner()); //$NON-NLS-1$
            assertEquals(Path.of("/tmp/codepilot-registry"), overridden.registryDirectory()); //$NON-NLS-1$

            System.clearProperty(McpHostInstanceRegistryPublisher.INSTANCE_ID_PROPERTY);
            System.clearProperty(McpHostInstanceRegistryPublisher.OWNER_PROPERTY);
            System.clearProperty(McpHostInstanceRegistryPublisher.REGISTRY_DIRECTORY_PROPERTY);
            McpHostInstanceRegistryPublisher defaults = McpHostInstanceRegistryPublisher.fromSystemProperties();
            assertTrue(defaults.instanceId().matches("[0-9a-fA-F-]{36}")); //$NON-NLS-1$
            assertEquals("external", defaults.owner()); //$NON-NLS-1$
            assertTrue(defaults.registryDirectory().endsWith(Path.of(".codepilot1c", "instances"))); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            restore(McpHostInstanceRegistryPublisher.INSTANCE_ID_PROPERTY, oldId);
            restore(McpHostInstanceRegistryPublisher.OWNER_PROPERTY, oldOwner);
            restore(McpHostInstanceRegistryPublisher.REGISTRY_DIRECTORY_PROPERTY, oldDirectory);
        }
    }

    @Test
    public void rejectsHostileInstanceIdAndUnknownOwner() {
        assertThrows(IllegalArgumentException.class, () -> new McpHostInstanceRegistryPublisher(
                "../../replace-me", "external", Path.of("/tmp"), FIXED_CLOCK, () -> 1L, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new NioInstanceRegistryFileSystem()));
        assertThrows(IllegalArgumentException.class, () -> new McpHostInstanceRegistryPublisher(
                INSTANCE_ID, "administrator", Path.of("/tmp"), FIXED_CLOCK, () -> 1L, //$NON-NLS-1$ //$NON-NLS-2$
                new NioInstanceRegistryFileSystem()));
    }

    @Test
    public void buildsConnectableLocalUrlsForWildcardAndIpv6Binds() {
        assertEquals("http://127.0.0.1:8765", McpHostInstanceEndpoint.localBaseUrl("0.0.0.0", 8765)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://127.0.0.1:8765", McpHostInstanceEndpoint.localBaseUrl("", 8765)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://[::1]:8765", McpHostInstanceEndpoint.localBaseUrl("::", 8765)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://[::1]:8765", McpHostInstanceEndpoint.localBaseUrl("[::]", 8765)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://[2001:db8::7]:8765", McpHostInstanceEndpoint.localBaseUrl("2001:db8::7", 8765)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://[2001:db8::7]:8765", McpHostInstanceEndpoint.localBaseUrl("[2001:db8::7]", 8765)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void doesNotSerializeBearerOrOAuthSecrets() throws Exception {
        Path directory = Files.createTempDirectory("codepilot-registry-test"); //$NON-NLS-1$
        try {
            McpHostInstanceRegistryPublisher publisher = publisher(INSTANCE_ID, "external", directory, 1); //$NON-NLS-1$
            publisher.publish(8765, "http://127.0.0.1:8765", "/workspace", "/edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "gui", "plugin", "BEARER_ONLY"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String json = Files.readString(publisher.registryFile());
            assertFalse(json.contains("super-secret-bearer-token")); //$NON-NLS-1$
            assertFalse(json.contains("access_token")); //$NON-NLS-1$
            assertFalse(json.contains("refresh_token")); //$NON-NLS-1$
            assertFalse(json.contains("Authorization")); //$NON-NLS-1$
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    public void neverDeletesAReplacementRegistryFile() throws Exception {
        Path directory = Files.createTempDirectory("codepilot-registry-test"); //$NON-NLS-1$
        try {
            McpHostInstanceRegistryPublisher first = publisher(INSTANCE_ID, "external", directory, 42);
            McpHostInstanceRegistryPublisher replacement = publisher(INSTANCE_ID, "external", directory, 42);
            assertTrue(first.publish(8765, "http://127.0.0.1:8765", "/workspace", "/edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "gui", "plugin", "NONE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(replacement.publish(8766, "http://127.0.0.1:8766", "/workspace", "/edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "gui", "plugin", "NONE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            assertFalse(first.unpublish());
            assertTrue(Files.exists(replacement.registryFile()));
            assertTrue(replacement.unpublish());
            assertFalse(Files.exists(replacement.registryFile()));
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    public void publishFailureIsNonFatal() {
        InstanceRegistryFileSystem failing = new InstanceRegistryFileSystem() {
            @Override
            public void writeAtomically(Path target, String json) throws IOException {
                throw new IOException("disk unavailable"); //$NON-NLS-1$
            }

            @Override
            public Optional<String> read(Path target) {
                return Optional.empty();
            }

            @Override
            public boolean deleteIfOwned(Path target, String instanceId, long pid, String nonce) {
                return false;
            }
        };
        McpHostInstanceRegistryPublisher publisher = new McpHostInstanceRegistryPublisher(
                INSTANCE_ID, "external", Path.of("/tmp/registry"), FIXED_CLOCK, () -> 9L, failing); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(publisher.publish(8765, "http://127.0.0.1:8765", "/workspace", "/edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "gui", "plugin", "NONE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(publisher.unpublish());
    }

    private static McpHostInstanceRegistryPublisher publisher(String id, String owner, Path directory, long pid) {
        return new McpHostInstanceRegistryPublisher(id, owner, directory, FIXED_CLOCK, () -> pid,
                new NioInstanceRegistryFileSystem());
    }

    private static Map<String, Object> record(Path file) throws IOException {
        return new Gson().fromJson(Files.readString(file), new TypeToken<Map<String, Object>>() { }.getType());
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (directory != null && Files.exists(directory)) {
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
