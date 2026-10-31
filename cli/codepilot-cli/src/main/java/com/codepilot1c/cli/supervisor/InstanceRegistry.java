/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.codepilot1c.cli.render.JsonWriter;

/** Atomic reader/writer for {@code ~/.codepilot1c/instances/*.json}. */
public final class InstanceRegistry {
    private final SupervisorFileSystem files;
    private final Path directory;

    public InstanceRegistry(SupervisorFileSystem files, Path directory) {
        this.files = files;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() { return directory; }

    public void write(InstanceRecord record) throws IOException {
        files.writeAtomically(path(record.instanceId()), JsonWriter.write(record.toJsonValue()) + System.lineSeparator());
    }

    public Optional<InstanceRecord> find(String id) throws IOException {
        Path path = path(id);
        if (!files.exists(path)) return Optional.empty();
        InstanceRecord record = parseEntry(files.readString(path)).record();
        if (!record.instanceId().equalsIgnoreCase(id)) throw new IllegalArgumentException("instance id mismatch");
        return Optional.of(record);
    }

    public List<InstanceRecord> list() throws IOException {
        return listEntries().stream().map(Entry::record).toList();
    }

    /** Reads records together with presence-sensitive broker discovery metadata. */
    public List<Entry> listEntries() throws IOException {
        List<Entry> result = new ArrayList<>();
        for (Path path : files.listJsonFiles(directory)) {
            try {
                Entry entry = parseEntry(files.readString(path));
                if ((entry.record().instanceId() + ".json").equalsIgnoreCase(path.getFileName().toString())) {
                    result.add(entry);
                }
            }
            catch (IllegalArgumentException ignored) { /* Ignore unrelated/corrupt files; never execute their contents. */ }
        }
        return result.stream().sorted((left, right) ->
                left.record().instanceId().compareTo(right.record().instanceId())).toList();
    }

    public void delete(String id) throws IOException { files.deleteIfExists(path(id)); }

    private Path path(String id) {
        String normalized;
        try { normalized = UUID.fromString(id).toString(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("invalid instance id"); }
        if (!normalized.equalsIgnoreCase(id)) throw new IllegalArgumentException("invalid instance id");
        return directory.resolve(normalized + ".json");
    }

    private static Entry parseEntry(String json) {
        Map<String, Object> value = FlatJsonObjectReader.read(json);
        List<String> capabilities = capabilities(value);
        InstanceRecord record = new InstanceRecord(integer(value, "schemaVersion"), string(value, "instanceId"),
                number(value, "pid"), integer(value, "port"), string(value, "baseUrl"),
                string(value, "workspace"), string(value, "edtHome"), string(value, "mode"),
                string(value, "owner"), Instant.parse(string(value, "startedAt")),
                nullableString(value, "pluginVersion"), nullableString(value, "authMode"),
                nullableString(value, "logFile"), capabilities);
        BrokerAdvertisement broker = capabilities.contains("llm.v1")
                ? BrokerAdvertisement.ADVERTISED
                : value.containsKey("capabilities") || value.containsKey("llmBrokerVersion")
                        ? BrokerAdvertisement.NOT_ADVERTISED
                        : BrokerAdvertisement.UNSPECIFIED;
        return new Entry(record, broker);
    }

    private static String string(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text)) throw new IllegalArgumentException("missing string: " + key);
        return text;
    }

    private static String nullableString(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (item == null) return null;
        if (!(item instanceof String text)) throw new IllegalArgumentException("invalid string: " + key);
        return text;
    }

    private static List<String> nullableStringList(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (item == null) return List.of();
        if (!(item instanceof List<?> items)) throw new IllegalArgumentException("invalid array: " + key);
        List<String> result = new ArrayList<>();
        for (Object element : items) {
            if (!(element instanceof String text)) throw new IllegalArgumentException("invalid array: " + key);
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static List<String> capabilities(Map<String, Object> value) {
        List<String> result = new ArrayList<>(nullableStringList(value, "capabilities"));
        Object brokerVersion = value.get("llmBrokerVersion");
        if (brokerVersion != null) {
            if (!(brokerVersion instanceof Long number) || number < 0 || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid integer: llmBrokerVersion");
            }
            if (number >= 1 && !result.contains("llm.v1")) result.add("llm.v1");
        }
        return List.copyOf(result);
    }

    private static long number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Long number)) throw new IllegalArgumentException("missing integer: " + key);
        return number;
    }

    private static int integer(Map<String, Object> value, String key) {
        return Math.toIntExact(number(value, key));
    }

    public enum BrokerAdvertisement {
        ADVERTISED,
        NOT_ADVERTISED,
        UNSPECIFIED
    }

    public record Entry(InstanceRecord record, BrokerAdvertisement brokerAdvertisement) { }
}
