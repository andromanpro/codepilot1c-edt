/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

/**
 * UI-facing, display-ready snapshot of GSD project state.
 *
 * <p>This is a pure data record — no SWT, no Eclipse, no core dependencies beyond
 * JDK types. Produced by {@link GsdUiSnapshotMapper} from a {@code GsdState} off the
 * UI thread; consumed by {@link GsdStatusPanel} on the UI thread.</p>
 *
 * @param phase            current lifecycle phase name (e.g. "DISCOVERY"), never {@code null}
 * @param goal             the project goal text (may be empty), never {@code null}
 * @param revision         optimistic-concurrency revision
 * @param activeSession    active session id (may be empty), never {@code null}
 * @param activeWorkstream active workstream id (may be empty), never {@code null}
 * @param tasksDone        count of tasks in DONE status
 * @param tasksTotal       total task count
 * @param currentWaveName  name of the first non-fully-done wave (may be empty), never {@code null}
 * @param evidenceCount    total evidence count
 * @param suggestedProfileId profile id suggested for the current phase (e.g. "gsd-discuss"), never {@code null}
 * @param loadError        last load error message (empty if loaded successfully), never {@code null}
 */
public record GsdUiSnapshot(
        String phase,
        String goal,
        long revision,
        String activeSession,
        String activeWorkstream,
        int tasksDone,
        int tasksTotal,
        String currentWaveName,
        int evidenceCount,
        String suggestedProfileId,
        String loadError) {

    /**
     * Canonical record constructor; enforces non-null defaults.
     */
    public GsdUiSnapshot {
        if (phase == null) {
            phase = "DISCOVERY"; //$NON-NLS-1$
        }
        if (goal == null) {
            goal = ""; //$NON-NLS-1$
        }
        if (activeSession == null) {
            activeSession = ""; //$NON-NLS-1$
        }
        if (activeWorkstream == null) {
            activeWorkstream = ""; //$NON-NLS-1$
        }
        if (currentWaveName == null) {
            currentWaveName = ""; //$NON-NLS-1$
        }
        if (suggestedProfileId == null) {
            suggestedProfileId = ""; //$NON-NLS-1$
        }
        if (loadError == null) {
            loadError = ""; //$NON-NLS-1$
        }
    }

    /**
     * Creates a snapshot representing a load failure.
     *
     * @param errorMessage the error message
     * @return an error snapshot
     */
    public static GsdUiSnapshot error(String errorMessage) {
        return new GsdUiSnapshot(
                "—", //$NON-NLS-1$
                "", //$NON-NLS-1$
                0L,
                "", //$NON-NLS-1$
                "", //$NON-NLS-1$
                0,
                0,
                "", //$NON-NLS-1$
                0,
                "", //$NON-NLS-1$
                errorMessage != null ? errorMessage : "Unknown error"); //$NON-NLS-1$
    }

    /**
     * Creates an empty/loading snapshot.
     *
     * @return an empty snapshot
     */
    public static GsdUiSnapshot empty() {
        return new GsdUiSnapshot(
                "—", //$NON-NLS-1$
                "", //$NON-NLS-1$
                0L,
                "", //$NON-NLS-1$
                "", //$NON-NLS-1$
                0,
                0,
                "", //$NON-NLS-1$
                0,
                "", //$NON-NLS-1$
                ""); //$NON-NLS-1$
    }

    /**
     * Whether this snapshot represents a successful load (no error).
     *
     * @return {@code true} if loaded successfully
     */
    public boolean isLoaded() {
        return loadError.isEmpty();
    }

    /**
     * Whether this snapshot has any task data.
     *
     * @return {@code true} if there are tasks
     */
    public boolean hasTasks() {
        return tasksTotal > 0;
    }

    /**
     * Formatted tasks progress string.
     *
     * @return e.g. "3/10" or "—" if no tasks
     */
    public String tasksProgress() {
        if (tasksTotal == 0) {
            return "—"; //$NON-NLS-1$
        }
        return tasksDone + "/" + tasksTotal; //$NON-NLS-1$
    }
}
