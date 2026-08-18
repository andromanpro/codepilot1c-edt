/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import org.junit.Assume;
import org.junit.Test;

public class PrivateUtf8SecretReaderTest {
    @Test public void readsTrimmedUtf8IntoCallerOwnedCharacters() throws IOException {
        Path path = privateFile("  секрет-token\r\n".getBytes(StandardCharsets.UTF_8));
        char[] value = PrivateUtf8SecretReader.read(path, 1024);
        try {
            assertArrayEquals("секрет-token".toCharArray(), value);
        } finally {
            Arrays.fill(value, '\0');
            Files.deleteIfExists(path);
        }
    }

    @Test public void rejectsMalformedOversizedAndSymlinkInputs() throws IOException {
        Path malformed = privateFile(new byte[] { (byte) 0xc3, (byte) 0x28 });
        Path oversized = privateFile("12345".getBytes(StandardCharsets.UTF_8));
        Path link = malformed.resolveSibling(malformed.getFileName() + "-link");
        try {
            assertThrows(PrivateUtf8SecretReader.SecretFileException.class,
                    () -> PrivateUtf8SecretReader.read(malformed, 1024));
            assertThrows(PrivateUtf8SecretReader.SecretFileException.class,
                    () -> PrivateUtf8SecretReader.read(oversized, 4));
            try {
                Files.createSymbolicLink(link, malformed);
            } catch (UnsupportedOperationException | IOException failure) {
                Assume.assumeNoException(failure);
            }
            assertThrows(PrivateUtf8SecretReader.SecretFileException.class,
                    () -> PrivateUtf8SecretReader.read(link, 1024));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(malformed);
            Files.deleteIfExists(oversized);
        }
    }

    @Test public void rejectsBroadPosixPermissions() throws IOException {
        Path path = privateFile("secret".getBytes(StandardCharsets.UTF_8));
        try {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException failure) {
                Assume.assumeNoException(failure);
            }
            assertThrows(PrivateUtf8SecretReader.SecretFileException.class,
                    () -> PrivateUtf8SecretReader.read(path, 1024));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static Path privateFile(byte[] content) throws IOException {
        Path path = Files.createTempFile("codepilot secret ", ".txt");
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // ACL-based test platform.
        }
        Files.write(path, content);
        return path;
    }
}
