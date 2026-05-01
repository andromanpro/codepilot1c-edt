/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProjectMemoryContextServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ProjectMemoryContextService service = new ProjectMemoryContextService();

    @Test
    public void missingReturnsMissingAndCanonicalTargetPath() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();

        ProjectMemoryContextService.ReadResult result = service.readForPrompt(root, 1024);

        assertEquals(ProjectMemoryContextService.Status.MISSING, result.getStatus());
        assertEquals(root.resolve("Code.md"), result.getSourcePath());
        assertEquals(0, result.getReadBytes());
        assertEquals(0, result.getSizeBytes());
        assertEquals("", result.getContent());
        assertFalse(result.isTruncated());
    }

    @Test
    public void stringOverloadsRejectNullBlankAndInvalidProjectRoot() throws Exception {
        Path outside = temporaryFolder.getRoot().toPath().resolve("Code.md");

        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT, service.status((String) null).getStatus());
        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT, service.find("").getStatus());
        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT, service.readForPrompt("   ", 1024).getStatus());
        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT,
                service.readFull("\u0000invalid").getStatus());
        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT,
                service.write((String) null, "content").getStatus());
        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT, service.write(" ", "content").getStatus());
        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT,
                service.write("\u0000invalid", "content").getStatus());
        assertFalse(Files.exists(outside));
    }

    @Test
    public void codeMdWinsOverUpperAndLowerCaseAliases() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Files.writeString(root.resolve("code.md"), "lower", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("CODE.md"), "upper", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Code.md"), "canonical", StandardCharsets.UTF_8);
        assumeTrue("filesystem must support distinct case-only aliases", hasExactChild(root, "Code.md")
                && hasExactChild(root, "CODE.md") && hasExactChild(root, "code.md"));

        ProjectMemoryContextService.ReadResult result = service.readFull(root);

        assertEquals(ProjectMemoryContextService.Status.FOUND, result.getStatus());
        assertEquals(root.resolve("Code.md"), result.getSourcePath());
        assertEquals("canonical", result.getContent());
    }

    @Test
    public void aliasPrecedenceIsDeterministicWithoutFilesystemCaseSupport() {
        Path root = Paths.get("/project");
        Map<String, Path> aliases = new LinkedHashMap<>();
        aliases.put("code.md", root.resolve("code.md"));
        aliases.put("CODE.md", root.resolve("CODE.md"));
        aliases.put("Code.md", root.resolve("Code.md"));

        assertEquals(root.resolve("Code.md"), ProjectMemoryContextService.findPreferredAlias(aliases));

        aliases.remove("Code.md");
        assertEquals(root.resolve("CODE.md"), ProjectMemoryContextService.findPreferredAlias(aliases));

        aliases.remove("CODE.md");
        assertEquals(root.resolve("code.md"), ProjectMemoryContextService.findPreferredAlias(aliases));
    }

    @Test
    public void fullReadReturnsCompleteContentWithoutPromptTruncation() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        String content = repeat("project memory\n", 200);
        Files.writeString(root.resolve("Code.md"), content, StandardCharsets.UTF_8);

        ProjectMemoryContextService.ReadResult result = service.readFull(root);

        assertEquals(ProjectMemoryContextService.Status.FOUND, result.getStatus());
        assertFalse(result.isTruncated());
        assertEquals(content, result.getContent());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, result.getReadBytes());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, result.getSizeBytes());
    }

    @Test
    public void budgetedReadTruncatesAndKeepsValidUtf8() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        String content = "abc\u20ac\u20acxyz";
        Files.writeString(root.resolve("Code.md"), content, StandardCharsets.UTF_8);

        ProjectMemoryContextService.ReadResult result = service.readForPrompt(root, 5);

        assertEquals(ProjectMemoryContextService.Status.TRUNCATED, result.getStatus());
        assertTrue(result.isTruncated());
        assertEquals("abc", result.getContent());
        assertEquals(3, result.getReadBytes());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, result.getSizeBytes());
        assertTrue(result.getWarning().contains("truncated"));
    }

    @Test
    public void budgetedReadReportsTruncatedWhenUtf8BoundaryLeavesEmptyContent() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        String content = "\u20acabc";
        Files.writeString(root.resolve("Code.md"), content, StandardCharsets.UTF_8);

        ProjectMemoryContextService.ReadResult result = service.readForPrompt(root, 1);

        assertEquals(ProjectMemoryContextService.Status.TRUNCATED, result.getStatus());
        assertTrue(result.isTruncated());
        assertEquals("", result.getContent());
        assertEquals(0, result.getReadBytes());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, result.getSizeBytes());
    }

    @Test
    public void emptyFileReturnsEmpty() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Files.write(root.resolve("Code.md"), new byte[0]);

        ProjectMemoryContextService.ReadResult result = service.readForPrompt(root, 1024);

        assertEquals(ProjectMemoryContextService.Status.EMPTY, result.getStatus());
        assertEquals("", result.getContent());
        assertFalse(result.isTruncated());
        assertEquals(0, result.getReadBytes());
        assertEquals(0, result.getSizeBytes());
    }

    @Test
    public void budgetedReadReturnsEmptyForBomOnlyFile() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Files.write(root.resolve("Code.md"), new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

        ProjectMemoryContextService.ReadResult result = service.readForPrompt(root, 3);

        assertEquals(ProjectMemoryContextService.Status.EMPTY, result.getStatus());
        assertEquals("", result.getContent());
        assertFalse(result.isTruncated());
        assertEquals(0, result.getReadBytes());
        assertEquals(3, result.getSizeBytes());
    }

    @Test
    public void writeCreatesCanonicalCodeMdWhenMissing() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();

        ProjectMemoryContextService.WriteResult result = service.write(root, "new memory");

        assertEquals(ProjectMemoryContextService.Status.FOUND, result.getStatus());
        assertEquals(root.resolve("Code.md"), result.getSourcePath());
        assertEquals("new memory", Files.readString(root.resolve("Code.md"), StandardCharsets.UTF_8));
    }

    @Test
    public void writeUpdatesExistingAlias() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Files.writeString(root.resolve("CODE.md"), "old", StandardCharsets.UTF_8);

        ProjectMemoryContextService.WriteResult result = service.write(root, "updated");

        assertEquals(ProjectMemoryContextService.Status.FOUND, result.getStatus());
        assertEquals(root.resolve("CODE.md"), result.getSourcePath());
        assertEquals("updated", Files.readString(root.resolve("CODE.md"), StandardCharsets.UTF_8));
        assertFalse(hasExactChild(root, "Code.md"));
    }

    @Test
    public void danglingSymlinkAliasFailsClosedAndDoesNotCreateOutsideTarget() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Path outsideTarget = temporaryFolder.getRoot().toPath().resolve("outside").resolve("Code.md");
        Files.createSymbolicLink(root.resolve("Code.md"), outsideTarget);

        ProjectMemoryContextService.ReadResult read = service.readFull(root);
        ProjectMemoryContextService.WriteResult write = service.write(root, "updated");

        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT, read.getStatus());
        assertEquals(ProjectMemoryContextService.Status.OUTSIDE_PROJECT, write.getStatus());
        assertFalse(Files.exists(outsideTarget));
    }

    @Test
    public void symlinkAliasInsideProjectIsAllowedForReadAndWrite() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Path memoryDir = Files.createDirectory(root.resolve("memory"));
        Path target = memoryDir.resolve("actual.md");
        Files.writeString(target, "inside", StandardCharsets.UTF_8);
        Files.createSymbolicLink(root.resolve("Code.md"), target);

        ProjectMemoryContextService.ReadResult read = service.readFull(root);
        ProjectMemoryContextService.WriteResult write = service.write(root, "updated");

        assertEquals(ProjectMemoryContextService.Status.FOUND, read.getStatus());
        assertEquals("inside", read.getContent());
        assertEquals(ProjectMemoryContextService.Status.FOUND, write.getStatus());
        assertEquals("updated", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    public void aliasScanFailureSurfacesAsReadOrWriteError() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        ProjectMemoryContextService.AliasScanResult failure = ProjectMemoryContextService.AliasScanResult
                .failure(root.resolve("Code.md"), new AccessDeniedException(root.toString()));

        ProjectMemoryContextService.ReadResult status = service.status(root, ignored -> failure);
        ProjectMemoryContextService.ReadResult find = service.find(root, ignored -> failure);
        ProjectMemoryContextService.ReadResult read = service.readFull(root, ignored -> failure);
        ProjectMemoryContextService.WriteResult write = service.write(root, "content", ignored -> failure);

        assertEquals(ProjectMemoryContextService.Status.READ_ERROR, status.getStatus());
        assertEquals(ProjectMemoryContextService.Status.READ_ERROR, find.getStatus());
        assertEquals(ProjectMemoryContextService.Status.READ_ERROR, read.getStatus());
        assertEquals(ProjectMemoryContextService.Status.WRITE_ERROR, write.getStatus());
        assertFalse(Files.exists(root.resolve("Code.md")));
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder(value.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean hasExactChild(Path root, String name) throws Exception {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                Path fileName = child.getFileName();
                if (fileName != null && name.equals(fileName.toString())) {
                    return true;
                }
            }
        }
        return false;
    }
}
