/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded, no-follow UTF-8 secret reader whose temporary byte/char backing is wiped. */
final class PrivateUtf8SecretReader {
    private PrivateUtf8SecretReader() { }

    static char[] read(Path path, int maximumBytes) throws SecretFileException {
        if (path == null || maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
            throw new SecretFileException();
        }
        byte[] bytes = null;
        char[] decoded = null;
        CharBuffer characters = null;
        boolean success = false;
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > maximumBytes || isBroadlyReadable(path)) {
                throw new SecretFileException();
            }

            bytes = new byte[maximumBytes + 1];
            ByteBuffer input = ByteBuffer.wrap(bytes);
            Set<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.READ);
            options.add(LinkOption.NOFOLLOW_LINKS);
            try (var channel = Files.newByteChannel(path, options)) {
                while (input.hasRemaining() && channel.read(input) != -1) {
                    // Keep reading until EOF or the explicit maximum plus sentinel byte.
                }
            }
            int byteCount = input.position();
            if (byteCount > maximumBytes) throw new SecretFileException();

            characters = CharBuffer.allocate(byteCount);
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer encoded = ByteBuffer.wrap(bytes, 0, byteCount);
            var decodeResult = decoder.decode(encoded, characters, true);
            if (decodeResult.isError()) decodeResult.throwException();
            var flushResult = decoder.flush(characters);
            if (flushResult.isError()) flushResult.throwException();

            char[] backing = characters.array();
            int start = 0;
            int end = characters.position();
            while (start < end && backing[start] <= ' ') start++;
            while (end > start && backing[end - 1] <= ' ') end--;
            if (start == end) throw new SecretFileException();
            decoded = Arrays.copyOfRange(backing, start, end);
            success = true;
            return decoded;
        } catch (IOException | RuntimeException failure) {
            throw failure instanceof SecretFileException secret ? secret : new SecretFileException(failure);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            if (characters != null && characters.hasArray()) Arrays.fill(characters.array(), '\0');
            if (!success && decoded != null) Arrays.fill(decoded, '\0');
        }
    }

    private static boolean isBroadlyReadable(Path path) throws IOException {
        try {
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            return permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    static final class SecretFileException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        SecretFileException() { }
        SecretFileException(Throwable cause) { super(cause); }
    }
}
