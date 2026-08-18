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
        InstanceRecord record = parse(files.readString(path));
        if (!record.instanceId().equalsIgnoreCase(id)) throw new IllegalArgumentException("instance id mismatch");
        return Optional.of(record);
    }

    public List<InstanceRecord> list() throws IOException {
        List<InstanceRecord> result = new ArrayList<>();
        for (Path path : files.listJsonFiles(directory)) {
            try {
                InstanceRecord record = parse(files.readString(path));
                if ((record.instanceId() + ".json").equalsIgnoreCase(path.getFileName().toString())) result.add(record);
            }
            catch (IllegalArgumentException ignored) { /* Ignore unrelated/corrupt files; never execute their contents. */ }
        }
        return result.stream().sorted((left, right) -> left.instanceId().compareTo(right.instanceId())).toList();
    }

    public void delete(String id) throws IOException { files.deleteIfExists(path(id)); }

    private Path path(String id) {
        String normalized;
        try { normalized = UUID.fromString(id).toString(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("invalid instance id"); }
        if (!normalized.equalsIgnoreCase(id)) throw new IllegalArgumentException("invalid instance id");
        return directory.resolve(normalized + ".json");
    }

    private static InstanceRecord parse(String json) {
        Map<String, Object> value = FlatJsonObjectReader.read(json);
        return new InstanceRecord(integer(value, "schemaVersion"), string(value, "instanceId"),
                number(value, "pid"), integer(value, "port"), string(value, "baseUrl"),
                string(value, "workspace"), string(value, "edtHome"), string(value, "mode"),
                string(value, "owner"), Instant.parse(string(value, "startedAt")),
                nullableString(value, "pluginVersion"), nullableString(value, "authMode"),
                nullableString(value, "logFile"));
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

    private static long number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Long number)) throw new IllegalArgumentException("missing integer: " + key);
        return number;
    }

    private static int integer(Map<String, Object> value, String key) {
        return Math.toIntExact(number(value, key));
    }
}
