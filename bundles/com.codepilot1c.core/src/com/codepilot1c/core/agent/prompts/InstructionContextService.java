/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codepilot1c.core.memory.project.ProjectMemoryContextService;

/**
 * Loads layered instruction files with source provenance.
 */
public final class InstructionContextService {

    private static final int CODE_PROMPT_BUDGET_BYTES = 64 * 1024;

    public enum LayerKind {
        AGENTS,
        CODE
    }

    public record InstructionLayer(
            LayerKind kind,
            String sourcePath,
            String content,
            String status,
            String warning,
            long readBytes,
            long sizeBytes) {

        public InstructionLayer(LayerKind kind, String sourcePath, String content) {
            this(kind, sourcePath, content, null, null, 0, 0);
        }
    }

    private final WorkspacePromptSourceResolver sourceResolver;

    public InstructionContextService() {
        this(new WorkspacePromptSourceResolver());
    }

    InstructionContextService(Path projectStart, Path userHome) {
        this(new WorkspacePromptSourceResolver(projectStart, userHome));
    }

    InstructionContextService(WorkspacePromptSourceResolver sourceResolver) {
        this.sourceResolver = sourceResolver;
    }

    public List<InstructionLayer> loadAgentsLayers() {
        return discoverLayers("AGENTS.md", LayerKind.AGENTS); //$NON-NLS-1$
    }

    public List<InstructionLayer> loadAgentsLayers(String projectPath) {
        return serviceForProjectPath(projectPath).loadAgentsLayers();
    }

    public List<InstructionLayer> loadCodeLayers(boolean backendSelectedInUi) {
        return List.of();
    }

    public List<InstructionLayer> loadCodeLayers(boolean backendSelectedInUi, String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return List.of();
        }
        try {
            Path projectRoot = Path.of(projectPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(projectRoot)) {
                return List.of();
            }
            return loadProjectMemoryCodeLayer(projectRoot);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private InstructionContextService serviceForProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return this;
        }
        try {
            Path projectStart = Path.of(projectPath).toAbsolutePath().normalize();
            Path currentStart = sourceResolver.getProjectStart();
            if (projectStart.equals(currentStart != null ? currentStart.toAbsolutePath().normalize() : null)) {
                return this;
            }
            return new InstructionContextService(sourceResolver.withProjectStart(projectStart));
        } catch (RuntimeException e) {
            return this;
        }
    }

    private List<InstructionLayer> discoverLayers(String fileName, LayerKind kind) {
        Map<String, InstructionLayer> layers = new LinkedHashMap<>();

        for (Path candidate : sourceResolver.hiddenPromptCandidates(fileName)) {
            addCandidate(layers, candidate, kind);
        }

        for (Path directory : sourceResolver.collectAncestors()) {
            addCandidate(layers, directCandidate(directory, fileName), kind);
        }

        return List.copyOf(layers.values());
    }

    private List<InstructionLayer> loadProjectMemoryCodeLayer(Path projectRoot) {
        ProjectMemoryContextService.ReadResult result =
                new ProjectMemoryContextService().readForPrompt(projectRoot, CODE_PROMPT_BUDGET_BYTES);
        ProjectMemoryContextService.Status status = result.getStatus();
        if (status != ProjectMemoryContextService.Status.FOUND
                && status != ProjectMemoryContextService.Status.TRUNCATED) {
            return List.of();
        }
        Path sourcePath = result.getSourcePath();
        String source = sourcePath != null ? sourcePath.toAbsolutePath().normalize().toString()
                : ProjectMemoryContextService.CANONICAL_FILE_NAME;
        return List.of(new InstructionLayer(
                LayerKind.CODE,
                source,
                result.getContent().strip(),
                status.name(),
                result.getWarning(),
                result.getReadBytes(),
                result.getSizeBytes()));
    }

    private void addCandidate(Map<String, InstructionLayer> layers, Path candidate, LayerKind kind) {
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return;
        }
        try {
            String content = Files.readString(candidate, StandardCharsets.UTF_8).strip();
            if (!content.isEmpty()) {
                Path source = candidate.toRealPath();
                layers.put(source.toString(), new InstructionLayer(kind, source.toString(), content));
            }
        } catch (Exception e) {
            // Ignore unreadable layer and keep remaining context discovery alive.
        }
    }

    private Path directCandidate(Path directory, String fileName) {
        return directory != null ? directory.resolve(fileName) : null;
    }
}
