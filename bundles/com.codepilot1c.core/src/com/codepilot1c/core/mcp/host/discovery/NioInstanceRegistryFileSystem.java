package com.codepilot1c.core.mcp.host.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** NIO implementation of the registry filesystem boundary. */
final class NioInstanceRegistryFileSystem implements InstanceRegistryFileSystem {

    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @Override
    public void writeAtomically(Path target, String json) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Registry target has no parent directory"); //$NON-NLS-1$
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictToOwner(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some Windows and network filesystems have no atomic rename support. The
                // registry remains best-effort, so retain a safe replacement fallback.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public Optional<String> read(Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
    }

    @Override
    public boolean deleteIfOwned(Path target, String instanceId, long pid, String nonce) throws IOException {
        Optional<String> current = read(target);
        if (current.isEmpty() || !isOwnedBy(current.get(), instanceId, pid, nonce)) {
            return false;
        }
        return Files.deleteIfExists(target);
    }

    private static boolean isOwnedBy(String json, String instanceId, long pid, String nonce) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            return instanceId.equals(stringValue(object, "instanceId")) //$NON-NLS-1$
                    && pid == longValue(object, "pid") //$NON-NLS-1$
                    && nonce.equals(stringValue(object, "nonce")); //$NON-NLS-1$
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String stringValue(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString()
                : null;
    }

    private static long longValue(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsLong()
                : Long.MIN_VALUE;
    }

    private static void restrictToOwner(Path target) {
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows and several mounted filesystems do not expose POSIX permissions.
        }
    }
}
