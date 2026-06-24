/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.gsd;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.eclipse.core.runtime.IPath;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * File store for GSD {@link PlanArtifact}s, one JSON per session.
 *
 * <p>Mirrors {@code FileSessionStore}: artifacts live in the plugin state
 * location under {@code planning/}, so they are internal (not in the EDT
 * project) and survive context resets. A {@link Path} constructor keeps it
 * unit-testable without a running workbench.</p>
 *
 * <pre>
 * {plugin-state}/planning/
 *   {session-id}.json
 * </pre>
 */
public class PlanArtifactStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(PlanArtifactStore.class);
    private static final String PLANNING_DIR = "planning"; //$NON-NLS-1$
    private static final String EXTENSION = ".json"; //$NON-NLS-1$

    private final Path planningDirectory;
    private final Gson gson;

    /**
     * Creates a store in the default plugin-state planning directory.
     */
    public PlanArtifactStore() {
        this(getDefaultPlanningDirectory());
    }

    /**
     * Creates a store in the given directory (used by tests).
     *
     * @param planningDirectory directory for plan artifacts
     */
    public PlanArtifactStore(Path planningDirectory) {
        this.planningDirectory = planningDirectory;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .create();
        ensureDirectoryExists();
    }

    private static Path getDefaultPlanningDirectory() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null) {
            IPath stateLoc = plugin.getStateLocation();
            return Path.of(stateLoc.toOSString()).resolve(PLANNING_DIR);
        }
        // Fallback for tests or when the plugin is not active.
        return Path.of(System.getProperty("user.home"), ".vibe-planning"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(planningDirectory);
        } catch (IOException e) {
            LOG.error("Не удалось создать директорию планов: %s", planningDirectory); //$NON-NLS-1$
        }
    }

    private Path getArtifactFile(String sessionId) {
        String safeId = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        return planningDirectory.resolve(safeId + EXTENSION);
    }

    /**
     * Persists the artifact (incremental write; call after each mutation).
     *
     * @param artifact the artifact to save (must have a session id)
     */
    public void save(PlanArtifact artifact) {
        if (artifact == null || artifact.getSessionId() == null || artifact.getSessionId().isBlank()) {
            throw new IllegalArgumentException("PlanArtifact must have a sessionId"); //$NON-NLS-1$
        }
        artifact.touch();
        Path file = getArtifactFile(artifact.getSessionId());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            gson.toJson(artifact, writer);
            LOG.debug("План сохранён: %s", artifact.getSessionId()); //$NON-NLS-1$
        } catch (IOException e) {
            LOG.error("Ошибка сохранения плана %s: %s", artifact.getSessionId(), e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Loads the artifact for a session, if present.
     *
     * @param sessionId session id
     * @return the artifact, or empty
     */
    public Optional<PlanArtifact> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Path file = getArtifactFile(sessionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PlanArtifact artifact = gson.fromJson(reader, PlanArtifact.class);
            return Optional.ofNullable(artifact);
        } catch (IOException e) {
            LOG.error("Ошибка загрузки плана %s: %s", sessionId, e.getMessage()); //$NON-NLS-1$
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.warn("Ошибка парсинга плана %s: %s", sessionId, e.getMessage()); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * Returns whether an artifact exists for the session.
     *
     * @param sessionId session id
     * @return true if present
     */
    public boolean exists(String sessionId) {
        return sessionId != null && !sessionId.isBlank() && Files.exists(getArtifactFile(sessionId));
    }

    /**
     * Deletes the artifact for a session.
     *
     * @param sessionId session id
     * @return true if a file was deleted
     */
    public boolean delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(getArtifactFile(sessionId));
        } catch (IOException e) {
            LOG.error("Ошибка удаления плана %s: %s", sessionId, e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Planning artifacts directory.
     *
     * @return directory path
     */
    public Path getPlanningDirectory() {
        return planningDirectory;
    }

    /**
     * ISO-8601 Instant adapter (matches FileSessionStore).
     */
    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            String value = in.nextString();
            if (value == null || value.isEmpty()) {
                return null;
            }
            return Instant.parse(value);
        }
    }
}
