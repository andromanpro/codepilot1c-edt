/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Confined, no-follow storage for private UTF-8 files.
 *
 * <p>On POSIX file systems the root is forced to {@code 0700} and files to
 * {@code 0600}. Platforms without POSIX permissions (notably Windows) use the
 * platform defaults; this class deliberately makes no claim that those defaults
 * are equivalent to a particular Windows ACL.</p>
 */
public class PrivateFileWriter {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path root;

    public PrivateFileWriter(Path root) {
        Objects.requireNonNull(root, "root"); //$NON-NLS-1$
        this.root = root.toAbsolutePath().normalize();
        if (this.root.getParent() == null) {
            throw new IllegalArgumentException("private storage must not be a file-system root"); //$NON-NLS-1$
        }
    }

    public final Path root() {
        return root;
    }

    /** Creates and secures the configured root without accepting a symlink as the root itself. */
    public synchronized void ensureDirectory() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) createDirectoryChain();
        BasicFileAttributes attributes = attributes(root);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("private storage root is not a directory"); //$NON-NLS-1$
        }
        setPosixPermissions(root, DIRECTORY_PERMISSIONS);
    }

    public synchronized boolean exists(String fileName) throws IOException {
        ensureDirectory();
        Path path = resolve(fileName);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegularFile(path);
        return true;
    }

    /** Reads at most {@code maximumBytes}, rejecting links, non-files, and malformed UTF-8. */
    public synchronized String readString(String fileName, int maximumBytes) throws IOException {
        if (maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumBytes must be positive and bounded"); //$NON-NLS-1$
        }
        ensureDirectory();
        Path path = resolve(fileName);
        BasicFileAttributes attributes = requireRegularFile(path);
        setPosixPermissions(path, FILE_PERMISSIONS);
        if (attributes.size() > maximumBytes) throw new IOException("private file exceeds read limit"); //$NON-NLS-1$

        byte[] bytes = new byte[maximumBytes + 1];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        Set<OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.READ);
        options.add(LinkOption.NOFOLLOW_LINKS);
        try (var channel = Files.newByteChannel(path, options)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Continue to EOF or the explicit sentinel byte.
            }
        }
        if (buffer.position() > maximumBytes) throw new IOException("private file exceeds read limit"); //$NON-NLS-1$
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, buffer.position())).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("private file is not valid UTF-8", exception); //$NON-NLS-1$
        }
    }

    /** Replaces one direct child using a same-directory temporary and an atomic move when available. */
    public synchronized void writeAtomically(String fileName, String content) throws IOException {
        Objects.requireNonNull(content, "content"); //$NON-NLS-1$
        ensureDirectory();
        Path target = resolve(fileName);
        rejectNonRegularTarget(target);
        Path temporary = createTemporary(target.getFileName().toString());
        boolean moved = false;
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            writeAndForce(temporary, bytes);
            replaceTemporary(temporary, target);
            moved = true;
            setPosixPermissions(target, FILE_PERMISSIONS);
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    /**
     * Appends exactly one line. A separator is inserted first when a previous
     * process left a truncated final line, preserving the next valid record.
     */
    public synchronized void appendLine(String fileName, String line, long maximumFileBytes) throws IOException {
        Objects.requireNonNull(line, "line"); //$NON-NLS-1$
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("appendLine content must be one line"); //$NON-NLS-1$
        }
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive"); //$NON-NLS-1$
        ensureDirectory();
        Path target = resolve(fileName);
        rejectNonRegularTarget(target);

        boolean existing = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        long currentSize = existing ? requireRegularFile(target).size() : 0L;
        if (existing) setPosixPermissions(target, FILE_PERMISSIONS);
        boolean needsBoundary = currentSize > 0 && lastByte(target, currentSize) != (byte) '\n';
        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
        long addition = Math.addExact(lineBytes.length, needsBoundary ? 2L : 1L);
        if (currentSize > maximumFileBytes - addition) {
            throw new IOException("private file exceeds append limit"); //$NON-NLS-1$
        }

        Set<OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.CREATE);
        options.add(StandardOpenOption.WRITE);
        options.add(StandardOpenOption.APPEND);
        options.add(LinkOption.NOFOLLOW_LINKS);
        try (var channel = openAppendChannel(target, options)) {
            if (needsBoundary) writeFully(channel, ByteBuffer.wrap(new byte[] { '\n' }));
            writeFully(channel, ByteBuffer.wrap(lineBytes));
            writeFully(channel, ByteBuffer.wrap(new byte[] { '\n' }));
            if (channel instanceof FileChannel fileChannel) fileChannel.force(true);
        }
        setPosixPermissions(target, FILE_PERMISSIONS);
    }

    /** Lists regular direct children ending in {@code suffix}; links are never followed. */
    public synchronized List<String> listFileNames(String suffix) throws IOException {
        Objects.requireNonNull(suffix, "suffix"); //$NON-NLS-1$
        ensureDirectory();
        List<String> result = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (!name.endsWith(suffix)) continue;
                BasicFileAttributes attributes = attributes(path);
                if (attributes.isRegularFile() && !attributes.isSymbolicLink()) result.add(name);
            }
        }
        Collections.sort(result);
        return List.copyOf(result);
    }

    /** Hook kept protected so failure behavior can be verified without weakening production paths. */
    protected void replaceTemporary(Path temporary, Path target) throws IOException {
        rejectNonRegularTarget(target);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Re-check before the non-atomic fallback. Replacing a newly raced-in
            // symlink is itself safe, but rejecting it keeps the API deterministic.
            rejectNonRegularTarget(target);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void createDirectoryChain() throws IOException {
        List<Path> missing = new ArrayList<>();
        Path cursor = root;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(cursor);
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IOException("private storage has no existing ancestor"); //$NON-NLS-1$
        BasicFileAttributes ancestor = attributes(cursor);
        if (!ancestor.isDirectory() || ancestor.isSymbolicLink()) {
            throw new IOException("private storage ancestor is not a directory"); //$NON-NLS-1$
        }
        Collections.reverse(missing);
        for (Path path : missing) {
            try {
                createDirectory(path);
            } catch (FileAlreadyExistsException raced) {
                // Validate the object created by the racing process below.
            }
            BasicFileAttributes attributes = attributes(path);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("private storage path is not a directory"); //$NON-NLS-1$
            }
            setPosixPermissions(path, DIRECTORY_PERMISSIONS);
        }
    }

    private Path resolve(String fileName) {
        Objects.requireNonNull(fileName, "fileName"); //$NON-NLS-1$
        Path relative = Path.of(fileName);
        if (fileName.isBlank() || relative.isAbsolute() || relative.getNameCount() != 1
                || ".".equals(fileName) || "..".equals(fileName)) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("private file name must be one path segment"); //$NON-NLS-1$
        }
        Path resolved = root.resolve(relative).normalize();
        if (!root.equals(resolved.getParent())) {
            throw new IllegalArgumentException("private file escapes storage root"); //$NON-NLS-1$
        }
        return resolved;
    }

    private static BasicFileAttributes requireRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("private path is not a regular file"); //$NON-NLS-1$
        }
        return attributes;
    }

    private static void rejectNonRegularTarget(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        requireRegularFile(target);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private Path createTemporary(String targetName) throws IOException {
        try {
            return Files.createTempFile(root, "." + targetName + ".", ".tmp", //$NON-NLS-1$ //$NON-NLS-2$
                    posixAttributes(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            return Files.createTempFile(root, "." + targetName + ".", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void createDirectory(Path path) throws IOException {
        try {
            Files.createDirectory(path, posixAttributes(DIRECTORY_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            Files.createDirectory(path);
        }
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
        try (var channel = Files.newByteChannel(path, options)) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            if (channel instanceof FileChannel fileChannel) fileChannel.force(true);
        }
    }

    private static java.nio.channels.SeekableByteChannel openAppendChannel(
            Path target, Set<OpenOption> options) throws IOException {
        try {
            return Files.newByteChannel(target, options, posixAttributes(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            return Files.newByteChannel(target, options);
        }
    }

    private static byte lastByte(Path path, long size) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (var channel = Files.newByteChannel(path, options)) {
            channel.position(size - 1);
            ByteBuffer one = ByteBuffer.allocate(1);
            if (channel.read(one) != 1) throw new IOException("could not inspect private file boundary"); //$NON-NLS-1$
            return one.array()[0];
        }
    }

    private static void writeFully(java.nio.channels.SeekableByteChannel channel, ByteBuffer bytes)
            throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static FileAttribute<?>[] posixAttributes(Set<PosixFilePermission> permissions) {
        try {
            return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(permissions) };
        } catch (UnsupportedOperationException ignored) {
            return new FileAttribute<?>[0];
        }
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX providers: platform-default ACLs only.
        }
    }
}
