/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class InstanceRegistryTest {
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test public void atomicallyRoundTripsEscapedCrossPlatformPaths() throws Exception {
        MemoryFiles files = new MemoryFiles();
        InstanceRegistry registry = new InstanceRegistry(files, Path.of("/registry"));
        InstanceRecord expected = new InstanceRecord(1, ID, 42, 8765, "http://127.0.0.1:8765",
                "C:\\EDT Workspaces\\quoted \"name\"", "C:\\Program Files\\1C EDT", "headless", "cli",
                Instant.parse("2026-08-18T07:00:00Z"), "1.2.3", "none", "/logs/a\\b.log");

        registry.write(expected);

        assertEquals(expected, registry.find(ID).orElseThrow());
        assertEquals(List.of(expected), registry.list());
        assertFalse(files.values.values().iterator().next().contains("\nsecret"));
    }

    @Test public void rejectsTraversalAndIgnoresMismatchedFileName() throws Exception {
        MemoryFiles files = new MemoryFiles();
        InstanceRegistry registry = new InstanceRegistry(files, Path.of("/registry"));
        assertThrows(IllegalArgumentException.class, () -> registry.find("../../process"));

        InstanceRecord record = new InstanceRecord(1, ID, 42, 8765, "http://127.0.0.1:8765", "/work",
                "/edt", "headless", "cli", Instant.EPOCH, null, null, "/log");
        files.values.put(Path.of("/registry/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.json"),
                com.codepilot1c.cli.render.JsonWriter.write(record.toJsonValue()));
        assertEquals(List.of(), registry.list());
    }

    @Test public void acceptsHostPublishedSchemaWithoutLogFileAndWithUnknownNonce() throws Exception {
        MemoryFiles files = new MemoryFiles();
        InstanceRegistry registry = new InstanceRegistry(files, Path.of("/registry"));
        files.values.put(Path.of("/registry/" + ID + ".json"), """
                {"schemaVersion":1,"instanceId":"11111111-2222-3333-4444-555555555555",\
                "pid":42,"port":8765,"baseUrl":"http://127.0.0.1:8765",\
                "workspace":"/work","edtHome":"/edt","mode":"headless","owner":"cli",\
                "startedAt":"2026-08-18T07:00:00Z","nonce":"host-owned-value"}
                """);

        InstanceRecord record = registry.find(ID).orElseThrow();
        assertEquals(null, record.logFile());
        assertEquals("cli", record.owner());
        assertEquals(List.of(record), registry.list());
    }

    @Test public void acceptsScalarBrokerVersionLegacyCapabilityArrayAndOlderRecords() throws Exception {
        MemoryFiles files = new MemoryFiles();
        InstanceRegistry registry = new InstanceRegistry(files, Path.of("/registry"));
        String base = """
                {"schemaVersion":1,"instanceId":"11111111-2222-3333-4444-555555555555",\
                "pid":42,"port":8765,"baseUrl":"http://127.0.0.1:8765",\
                "workspace":"/work","edtHome":"/edt","mode":"headless","owner":"cli",\
                "startedAt":"2026-08-18T07:00:00Z"%s}
                """;

        files.values.put(Path.of("/registry/" + ID + ".json"), base.formatted(",\"llmBrokerVersion\":1"));
        InstanceRecord capable = registry.find(ID).orElseThrow();
        assertEquals(1, capable.schemaVersion());
        assertEquals("cli", capable.owner());
        assertEquals(List.of("llm.v1"), capable.capabilities());
        assertEquals(InstanceRegistry.BrokerAdvertisement.ADVERTISED,
                registry.listEntries().get(0).brokerAdvertisement());
        assertEquals(1, capable.toJsonValue().get("llmBrokerVersion"));
        assertFalse(capable.toJsonValue().containsKey("capabilities"));

        files.values.put(Path.of("/registry/" + ID + ".json"), base.formatted(",\"capabilities\":[\"llm.v1\"]"));
        assertEquals(List.of("llm.v1"), registry.find(ID).orElseThrow().capabilities());

        files.values.put(Path.of("/registry/" + ID + ".json"), base.formatted(""));
        InstanceRecord older = registry.find(ID).orElseThrow();
        assertEquals(List.of(), older.capabilities());
        assertEquals(InstanceRegistry.BrokerAdvertisement.UNSPECIFIED,
                registry.listEntries().get(0).brokerAdvertisement());
        assertFalse(older.toJsonValue().containsKey("capabilities"));
        assertFalse(older.toJsonValue().containsKey("llmBrokerVersion"));

        files.values.put(Path.of("/registry/" + ID + ".json"), base.formatted(",\"llmBrokerVersion\":0"));
        assertEquals(InstanceRegistry.BrokerAdvertisement.NOT_ADVERTISED,
                registry.listEntries().get(0).brokerAdvertisement());
    }

    @Test public void rejectsRecordIdentityMismatchAndNonLoopbackEndpoint() throws Exception {
        MemoryFiles files = new MemoryFiles();
        InstanceRegistry registry = new InstanceRegistry(files, Path.of("/registry"));
        String other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        InstanceRecord record = new InstanceRecord(1, other, 42, 8765, "http://127.0.0.1:8765", "/work",
                "/edt", "headless", "cli", Instant.EPOCH, null, null, null);
        files.values.put(Path.of("/registry/" + ID + ".json"),
                com.codepilot1c.cli.render.JsonWriter.write(record.toJsonValue()));
        assertThrows(IllegalArgumentException.class, () -> registry.find(ID));

        assertThrows(IllegalArgumentException.class, () -> new InstanceRecord(1, ID, 42, 8765,
                "http://example.invalid:8765", "/work", "/edt", "headless", "cli", Instant.EPOCH,
                null, null, null));
    }

    static final class MemoryFiles implements SupervisorFileSystem {
        final Map<Path, String> values = new HashMap<>();
        @Override public Path canonicalDirectory(String value) { return Path.of(value).toAbsolutePath().normalize(); }
        @Override public boolean exists(Path path) { return values.containsKey(path); }
        @Override public void createDirectories(Path path) { }
        @Override public void writeAtomically(Path path, String content) { values.put(path, content); }
        @Override public String readString(Path path) throws IOException {
            String result = values.get(path);
            if (result == null) throw new IOException("missing");
            return result;
        }
        @Override public List<Path> listJsonFiles(Path directory) {
            return values.keySet().stream().filter(path -> directory.equals(path.getParent())).sorted().toList();
        }
        @Override public void deleteIfExists(Path path) { values.remove(path); }
    }
}
