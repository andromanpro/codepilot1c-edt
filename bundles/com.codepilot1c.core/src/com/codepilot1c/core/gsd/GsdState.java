/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.List;
import java.util.Objects;

/**
 * Typed model of GSD project-level state. {@code state.json} is the single source of truth;
 * {@code STATE.md} and {@code PLAN.md} are deterministic projections (see {@link GsdProjections}).
 *
 * <p>{@code revision} is the optimistic-concurrency token returned by {@link GsdStateStore#load()}
 * and validated on {@link GsdStateStore#save(GsdState)}. {@code schemaVersion} is fixed at
 * {@link #CURRENT_SCHEMA_VERSION}; a mismatch on load is treated as corruption.</p>
 *
 * @param schemaVersion  schema version of this state (must equal {@link #CURRENT_SCHEMA_VERSION})
 * @param revision       monotonic optimistic-concurrency revision (0 for a fresh state)
 * @param phase          current lifecycle phase (never {@code null})
 * @param goal           the project goal (may be blank during {@link GsdPhase#DISCOVERY})
 * @param decisions      captured decisions (never {@code null})
 * @param tasks          work items (never {@code null})
 * @param waves          ordered waves (never {@code null})
 * @param evidence       evidence records (never {@code null})
 * @param sessionPointer active session/workstream pointer (never {@code null})
 */
public record GsdState(
        int schemaVersion,
        long revision,
        GsdPhase phase,
        String goal,
        List<GsdDecision> decisions,
        List<GsdTask> tasks,
        List<GsdWave> waves,
        List<GsdEvidence> evidence,
        GsdSessionPointer sessionPointer) {

    /** Current schema version; a stored state with a different value is treated as corruption. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Revision assigned to a freshly created state that has never been persisted. */
    public static final long INITIAL_REVISION = 0L;

    /**
     * Canonical record constructor; defensive-copies lists and enforces non-null defaults.
     */
    public GsdState {
        if (phase == null) {
            phase = GsdPhase.DISCOVERY;
        }
        if (goal == null) {
            goal = ""; //$NON-NLS-1$
        }
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        waves = waves == null ? List.of() : List.copyOf(waves);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        sessionPointer = sessionPointer == null ? GsdSessionPointer.empty() : sessionPointer;
    }

    /**
     * Returns a fresh, empty state at {@link GsdPhase#DISCOVERY} with {@link #INITIAL_REVISION}.
     *
     * @return the fresh state
     */
    public static GsdState fresh() {
        return new GsdState(
                CURRENT_SCHEMA_VERSION,
                INITIAL_REVISION,
                GsdPhase.DISCOVERY,
                "", //$NON-NLS-1$
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                GsdSessionPointer.empty());
    }

    /**
     * Returns a copy of this state with the given revision (used by the store on save).
     *
     * @param newRevision the new revision
     * @return a copy with the new revision
     */
    public GsdState withRevision(long newRevision) {
        return new GsdState(
                schemaVersion,
                newRevision,
                phase,
                goal,
                decisions,
                tasks,
                waves,
                evidence,
                sessionPointer);
    }

    /**
     * Returns a copy of this state with the given phase.
     *
     * @param newPhase the new phase
     * @return a copy with the new phase
     */
    public GsdState withPhase(GsdPhase newPhase) {
        return new GsdState(
                schemaVersion,
                revision,
                Objects.requireNonNull(newPhase, "newPhase"), //$NON-NLS-1$
                goal,
                decisions,
                tasks,
                waves,
                evidence,
                sessionPointer);
    }
}