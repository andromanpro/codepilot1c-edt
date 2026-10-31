/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrivateFileWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void createsOwnerOnlyPosixDirectoryAndFiles() throws Exception {
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix")); //$NON-NLS-1$
        Path root = temporary.getRoot().toPath().resolve("private/sessions"); //$NON-NLS-1$
        PrivateFileWriter writer = new PrivateFileWriter(root);
        writer.writeAtomically("meta.json", "value"); //$NON-NLS-1$ //$NON-NLS-2$
        writer.appendLine("messages.jsonl", "{}", 1024); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(root));
        Set<PosixFilePermission> expectedFile = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertEquals(expectedFile, Files.getPosixFilePermissions(root.resolve("meta.json"))); //$NON-NLS-1$
        assertEquals(expectedFile, Files.getPosixFilePermissions(root.resolve("messages.jsonl"))); //$NON-NLS-1$
    }

    @Test public void rejectsSymlinkRootAndSymlinkFileWithoutFollowingEither() throws Exception {
        Path outside = temporary.newFolder("outside").toPath(); //$NON-NLS-1$
        Path linkRoot = temporary.getRoot().toPath().resolve("linked-root"); //$NON-NLS-1$
        try {
            Files.createSymbolicLink(linkRoot, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assume.assumeNoException(unavailable);
        }
        assertThrows(IOException.class, () -> new PrivateFileWriter(linkRoot).ensureDirectory());

        Path root = temporary.newFolder("real-root").toPath(); //$NON-NLS-1$
        Path victim = outside.resolve("victim.txt"); //$NON-NLS-1$
        Files.writeString(victim, "unchanged"); //$NON-NLS-1$
        Files.createSymbolicLink(root.resolve("meta.json"), victim); //$NON-NLS-1$
        PrivateFileWriter writer = new PrivateFileWriter(root);
        assertThrows(IOException.class, () -> writer.writeAtomically("meta.json", "replacement")); //$NON-NLS-1$ //$NON-NLS-2$
        assertThrows(IOException.class, () -> writer.readString("meta.json", 100)); //$NON-NLS-1$
        assertEquals("unchanged", Files.readString(victim)); //$NON-NLS-1$
    }

    @Test public void boundedReadRejectsOversizedContent() throws Exception {
        Path root = temporary.newFolder("bounded").toPath(); //$NON-NLS-1$
        PrivateFileWriter writer = new PrivateFileWriter(root);
        writer.writeAtomically("value.txt", "12345"); //$NON-NLS-1$ //$NON-NLS-2$
        assertThrows(IOException.class, () -> writer.readString("value.txt", 4)); //$NON-NLS-1$
        assertEquals("12345", writer.readString("value.txt", 5)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test public void failedAtomicReplacementPreservesOldTargetAndCleansTemporary() throws Exception {
        Path root = temporary.newFolder("atomic").toPath(); //$NON-NLS-1$
        PrivateFileWriter writer = new PrivateFileWriter(root);
        writer.writeAtomically("meta.json", "old"); //$NON-NLS-1$ //$NON-NLS-2$
        PrivateFileWriter failing = new PrivateFileWriter(root) {
            @Override protected void replaceTemporary(Path temporary, Path target) throws IOException {
                throw new IOException("injected move failure"); //$NON-NLS-1$
            }
        };

        assertThrows(IOException.class, () -> failing.writeAtomically("meta.json", "new")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("old", writer.readString("meta.json", 100)); //$NON-NLS-1$ //$NON-NLS-2$
        try (var files = Files.list(root)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"))); //$NON-NLS-1$
        }
    }

    @Test public void rejectsTraversalNames() throws Exception {
        PrivateFileWriter writer = new PrivateFileWriter(temporary.newFolder("traversal").toPath()); //$NON-NLS-1$
        assertThrows(IllegalArgumentException.class, () -> writer.writeAtomically("../escape", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertThrows(IllegalArgumentException.class, () -> writer.writeAtomically("a/b", "x")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
