/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ProjectMemoryContextService {

    public static final String CANONICAL_FILE_NAME = "Code.md"; //$NON-NLS-1$

    private static final String[] ALIASES = { CANONICAL_FILE_NAME, "CODE.md", "code.md" }; //$NON-NLS-1$ //$NON-NLS-2$
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    public enum Status {
        FOUND,
        MISSING,
        EMPTY,
        TRUNCATED,
        READ_ERROR,
        WRITE_ERROR,
        OUTSIDE_PROJECT
    }

    public ReadResult status(String projectRootPath) {
        return status(parseRoot(projectRootPath));
    }

    public ReadResult status(Path projectRoot) {
        return status(projectRoot, ProjectMemoryContextService::findExistingAlias);
    }

    ReadResult status(Path projectRoot, AliasFinder aliasFinder) {
        return find(projectRoot, aliasFinder);
    }

    public ReadResult find(String projectRootPath) {
        return find(parseRoot(projectRootPath));
    }

    public ReadResult find(Path projectRoot) {
        return find(projectRoot, ProjectMemoryContextService::findExistingAlias);
    }

    ReadResult find(Path projectRoot, AliasFinder aliasFinder) {
        Path root = normalizeRoot(projectRoot);
        if (root == null) {
            return ReadResult.empty(Status.OUTSIDE_PROJECT, null, "Project root path is invalid"); //$NON-NLS-1$
        }
        AliasScanResult scan = aliasFinder.find(root);
        if (scan.error != null) {
            return ReadResult.empty(Status.READ_ERROR, scan.sourcePath, scan.error.getMessage());
        }
        Path existing = scan.sourcePath;
        if (existing == null) {
            return ReadResult.empty(Status.MISSING, root.resolve(CANONICAL_FILE_NAME), null);
        }
        Status status = isInsideProject(root, existing) ? Status.FOUND : Status.OUTSIDE_PROJECT;
        return ReadResult.empty(status, existing, status == Status.OUTSIDE_PROJECT ? "Project memory file is outside project" : null); //$NON-NLS-1$
    }

    public ReadResult readForPrompt(String projectRootPath, int budgetBytes) {
        return readForPrompt(parseRoot(projectRootPath), budgetBytes);
    }

    public ReadResult readForPrompt(Path projectRoot, int budgetBytes) {
        return readForPrompt(projectRoot, budgetBytes, ProjectMemoryContextService::findExistingAlias);
    }

    ReadResult readForPrompt(Path projectRoot, int budgetBytes, AliasFinder aliasFinder) {
        Path root = normalizeRoot(projectRoot);
        if (root == null) {
            return ReadResult.empty(Status.OUTSIDE_PROJECT, null, "Project root path is invalid"); //$NON-NLS-1$
        }
        AliasScanResult scan = aliasFinder.find(root);
        if (scan.error != null) {
            return ReadResult.empty(Status.READ_ERROR, scan.sourcePath, scan.error.getMessage());
        }
        Path source = scan.sourcePath;
        if (source == null) {
            return ReadResult.empty(Status.MISSING, root.resolve(CANONICAL_FILE_NAME), null);
        }
        if (!isInsideProject(root, source)) {
            return ReadResult.empty(Status.OUTSIDE_PROJECT, source, "Project memory file is outside project"); //$NON-NLS-1$
        }
        int safeBudget = Math.max(0, budgetBytes);
        try {
            long size = Files.size(source);
            if (size == 0) {
                return new ReadResult(Status.EMPTY, false, 0, 0, source, "", null); //$NON-NLS-1$
            }
            byte[] bytes = readAtMost(source, safeBudget);
            int safeLength = safeUtf8Length(bytes);
            byte[] safeBytes = stripBom(Arrays.copyOf(bytes, safeLength));
            String content = new String(safeBytes, StandardCharsets.UTF_8);
            boolean truncated = size > safeBudget || safeLength < bytes.length;
            Status status = truncated ? Status.TRUNCATED : safeBytes.length == 0 ? Status.EMPTY : Status.FOUND;
            String warning = truncated ? "Project memory content truncated to prompt byte budget" : null; //$NON-NLS-1$
            return new ReadResult(status, truncated, safeBytes.length, size, source, content, warning);
        } catch (IOException e) {
            return ReadResult.empty(Status.READ_ERROR, source, e.getMessage());
        }
    }

    public ReadResult readFull(String projectRootPath) {
        return readFull(parseRoot(projectRootPath));
    }

    public ReadResult readFull(Path projectRoot) {
        return readFull(projectRoot, ProjectMemoryContextService::findExistingAlias);
    }

    ReadResult readFull(Path projectRoot, AliasFinder aliasFinder) {
        Path root = normalizeRoot(projectRoot);
        if (root == null) {
            return ReadResult.empty(Status.OUTSIDE_PROJECT, null, "Project root path is invalid"); //$NON-NLS-1$
        }
        AliasScanResult scan = aliasFinder.find(root);
        if (scan.error != null) {
            return ReadResult.empty(Status.READ_ERROR, scan.sourcePath, scan.error.getMessage());
        }
        Path source = scan.sourcePath;
        if (source == null) {
            return ReadResult.empty(Status.MISSING, root.resolve(CANONICAL_FILE_NAME), null);
        }
        if (!isInsideProject(root, source)) {
            return ReadResult.empty(Status.OUTSIDE_PROJECT, source, "Project memory file is outside project"); //$NON-NLS-1$
        }
        try {
            byte[] bytes = Files.readAllBytes(source);
            long size = bytes.length;
            byte[] contentBytes = stripBom(bytes);
            Status status = contentBytes.length == 0 ? Status.EMPTY : Status.FOUND;
            String content = new String(contentBytes, StandardCharsets.UTF_8);
            return new ReadResult(status, false, contentBytes.length, size, source, content, null);
        } catch (IOException e) {
            return ReadResult.empty(Status.READ_ERROR, source, e.getMessage());
        }
    }

    public WriteResult write(String projectRootPath, String content) {
        return write(parseRoot(projectRootPath), content);
    }

    public WriteResult write(Path projectRoot, String content) {
        return write(projectRoot, content, ProjectMemoryContextService::findExistingAlias);
    }

    WriteResult write(Path projectRoot, String content, AliasFinder aliasFinder) {
        Path root = normalizeRoot(projectRoot);
        if (root == null) {
            return new WriteResult(Status.OUTSIDE_PROJECT, null, "Project root path is invalid"); //$NON-NLS-1$
        }
        AliasScanResult scan = aliasFinder.find(root);
        if (scan.error != null) {
            return new WriteResult(Status.WRITE_ERROR, scan.sourcePath, scan.error.getMessage());
        }
        Path target = scan.sourcePath;
        if (target == null) {
            target = root.resolve(CANONICAL_FILE_NAME);
        } else if (!isInsideProject(root, target)) {
            return new WriteResult(Status.OUTSIDE_PROJECT, target, "Project memory file is outside project"); //$NON-NLS-1$
        }
        try {
            Files.createDirectories(root);
            Files.writeString(target, Objects.requireNonNullElse(content, ""), StandardCharsets.UTF_8); //$NON-NLS-1$
            return new WriteResult(Status.FOUND, target, null);
        } catch (IOException e) {
            return new WriteResult(Status.WRITE_ERROR, target, e.getMessage());
        }
    }

    private static Path parseRoot(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            return null;
        }
        try {
            return Path.of(projectRootPath);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static Path normalizeRoot(Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private static AliasScanResult findExistingAlias(Path root) {
        if (!Files.isDirectory(root)) {
            return AliasScanResult.missing();
        }
        Map<String, Path> exactChildren = new HashMap<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                Path fileName = child.getFileName();
                if (fileName != null) {
                    exactChildren.put(fileName.toString(), child.toAbsolutePath().normalize());
                }
            }
        } catch (IOException e) {
            return AliasScanResult.failure(root.resolve(CANONICAL_FILE_NAME), e);
        }
        return AliasScanResult.found(findPreferredAlias(exactChildren));
    }

    static Path findPreferredAlias(Map<String, Path> exactChildren) {
        for (String alias : ALIASES) {
            Path candidate = exactChildren.get(alias);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isInsideProject(Path root, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return false;
        }
        try {
            Path realRoot = root.toRealPath();
            Path realFile = file.toRealPath();
            return realFile.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAtMost(Path source, int budgetBytes) throws IOException {
        if (budgetBytes == 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[budgetBytes];
        int offset = 0;
        try (InputStream input = Files.newInputStream(source)) {
            while (offset < budgetBytes) {
                int read = input.read(buffer, offset, budgetBytes - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return Arrays.copyOf(buffer, offset);
    }

    private static int safeUtf8Length(byte[] bytes) {
        for (int length = bytes.length; length >= 0; length--) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, length));
                return length;
            } catch (CharacterCodingException e) {
                // Try a shorter prefix until the byte slice is valid UTF-8.
            }
        }
        return 0;
    }

    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= UTF8_BOM.length
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2]) {
            return Arrays.copyOfRange(bytes, UTF8_BOM.length, bytes.length);
        }
        return bytes;
    }

    @FunctionalInterface
    interface AliasFinder {
        AliasScanResult find(Path root);
    }

    static final class AliasScanResult {

        private final Path sourcePath;
        private final IOException error;

        private AliasScanResult(Path sourcePath, IOException error) {
            this.sourcePath = sourcePath;
            this.error = error;
        }

        static AliasScanResult found(Path sourcePath) {
            return new AliasScanResult(sourcePath, null);
        }

        static AliasScanResult missing() {
            return new AliasScanResult(null, null);
        }

        static AliasScanResult failure(Path sourcePath, IOException error) {
            return new AliasScanResult(sourcePath, error);
        }
    }

    public static final class ReadResult {

        private final Status status;
        private final boolean truncated;
        private final long readBytes;
        private final long sizeBytes;
        private final Path sourcePath;
        private final String content;
        private final String warning;

        private ReadResult(Status status, boolean truncated, long readBytes, long sizeBytes, Path sourcePath, String content,
                String warning) {
            this.status = status;
            this.truncated = truncated;
            this.readBytes = readBytes;
            this.sizeBytes = sizeBytes;
            this.sourcePath = sourcePath;
            this.content = content;
            this.warning = warning;
        }

        private static ReadResult empty(Status status, Path sourcePath, String warning) {
            return new ReadResult(status, false, 0, 0, sourcePath, "", warning); //$NON-NLS-1$
        }

        public Status getStatus() {
            return status;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public long getReadBytes() {
            return readBytes;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public Path getSourcePath() {
            return sourcePath;
        }

        public String getContent() {
            return content;
        }

        public String getWarning() {
            return warning;
        }
    }

    public static final class WriteResult {

        private final Status status;
        private final Path sourcePath;
        private final String warning;

        private WriteResult(Status status, Path sourcePath, String warning) {
            this.status = status;
            this.sourcePath = sourcePath;
            this.warning = warning;
        }

        public Status getStatus() {
            return status;
        }

        public Path getSourcePath() {
            return sourcePath;
        }

        public String getWarning() {
            return warning;
        }
    }
}
