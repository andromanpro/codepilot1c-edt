/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * Provider-neutral service that performs GSD workflow operations on a {@link GsdState}
 * persisted by a {@link GsdStateStore}. All mutations are optimistic-concurrency aware
 * (expected revision) and validated by {@link GsdGuard} before persistence.
 *
 * <p><strong>Service-level phase guards</strong> — the profile tool allowlist is
 * <em>not</em> a security boundary. Each operation checks the current phase at the
 * service layer:</p>
 * <ul>
 *   <li>{@link #recordDecision} — DISCOVERY only</li>
 *   <li>{@link #createPlan} — PLANNING only</li>
 *   <li>{@link #updateTask} — EXECUTING only</li>
 *   <li>{@link #recordEvidence} — EXECUTING or VERIFYING</li>
 *   <li>{@link #transitionPhase} — any phase (transition rules enforced separately)</li>
 * </ul>
 *
 * <p>The state machine is {@link GsdPhase#DISCOVERY} &rarr; {@link GsdPhase#PLANNING} &rarr;
 * {@link GsdPhase#EXECUTING} &rarr; {@link GsdPhase#VERIFYING} &rarr; {@link GsdPhase#CLOSED}
 * with a single allowed rollback: {@link GsdPhase#VERIFYING} &rarr; {@link GsdPhase#EXECUTING}
 * (requires a non-blank reason, which is recorded as an audit decision). Phase changes are
 * performed exclusively by {@link #transitionPhase}; {@link #createPlan} never changes the phase.</p>
 */
public final class GsdWorkflowService {

    private GsdWorkflowService() {
    }

    // ---- Revision guard ---------------------------------------------------

    /**
     * Validates that the loaded state's revision matches the caller's expected revision.
     * Throws {@link GsdStaleRevisionException} on mismatch.
     */
    private static void checkRevision(GsdState state, long expectedRevision) {
        if (state.revision() != expectedRevision) {
            throw new GsdStaleRevisionException(expectedRevision, state.revision());
        }
    }

    // ---- Phase-transition helpers -----------------------------------------

    /**
     * Transitions the phase forward or (once) backward. Validates preconditions.
     *
     * <p>Entry guards:</p>
     * <ul>
     *   <li>EXECUTING: requires non-blank goal, non-empty tasks, non-empty waves</li>
     *   <li>VERIFYING: all tasks must be DONE</li>
     *   <li>CLOSED: all tasks DONE + non-INFERRED evidence (enforced by {@link GsdGuard})</li>
     * </ul>
     *
     * <p>A VERIFYING&rarr;EXECUTING rollback records an audit decision with a deterministic
     * id {@code rollback-r<revision>}, summary "Verification rollback", and the caller's
     * reason as rationale.</p>
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param targetPhase       the phase to transition to
     * @param reason            required only for VERIFYING&rarr;EXECUTING rollback; may be blank otherwise
     * @return the persisted state after transition
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if the target state violates invariants
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalArgumentException  if the transition is illegal or entry guard fails
     */
    public static GsdState transitionPhase(String projectRoot, long expectedRevision,
            GsdPhase targetPhase, String reason) throws IOException {
        Objects.requireNonNull(targetPhase, "targetPhase"); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        validateTransition(current.phase(), targetPhase, reason);
        checkRevision(current, expectedRevision);

        // Entry guard for EXECUTING: goal+tasks+waves required.
        if (targetPhase == GsdPhase.EXECUTING) {
            validateExecutingEntry(current);
        }
        // Entry guard for VERIFYING: all tasks must be DONE.
        if (targetPhase == GsdPhase.VERIFYING) {
            validateVerifyingEntry(current);
        }

        GsdState next;
        if (current.phase() == GsdPhase.VERIFYING && targetPhase == GsdPhase.EXECUTING) {
            // Rollback: record an audit decision with the rollback reason.
            next = current.withPhase(targetPhase);
            String auditId = "rollback-r" + current.revision(); //$NON-NLS-1$
            List<GsdDecision> decisions = new ArrayList<>(current.decisions());
            decisions.add(new GsdDecision(auditId, "Verification rollback", reason, List.of())); //$NON-NLS-1$
            next = new GsdState(
                    next.schemaVersion(),
                    next.revision(),
                    next.phase(),
                    next.goal(),
                    decisions,
                    next.tasks(),
                    next.waves(),
                    next.evidence(),
                    next.sessionPointer());
        } else {
            next = current.withPhase(targetPhase);
        }

        return store.save(next);
    }

    /**
     * Validates whether a transition from {@code from} to {@code to} is legal.
     *
     * @param from   current phase
     * @param to     target phase
     * @param reason rollback reason (required for VERIFYING&rarr;EXECUTING)
     * @throws IllegalArgumentException if the transition is illegal
     */
    public static void validateTransition(GsdPhase from, GsdPhase to, String reason) {
        if (from == to) {
            throw new IllegalArgumentException("already in phase " + to); //$NON-NLS-1$
        }
        int fromOrd = from.ordinal();
        int toOrd = to.ordinal();

        // Allowed rollback: VERIFYING -> EXECUTING only with a required reason.
        if (from == GsdPhase.VERIFYING && to == GsdPhase.EXECUTING) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "rollback VERIFYING->EXECUTING requires a non-blank reason"); //$NON-NLS-1$
            }
            return;
        }
        // Forward only: target ordinal must be exactly one more.
        if (toOrd == fromOrd + 1) {
            return;
        }
        throw new IllegalArgumentException(
                "illegal phase transition: " + from + " -> " + to); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Entry guard: EXECUTING requires non-blank goal, non-empty tasks, non-empty waves.
     */
    private static void validateExecutingEntry(GsdState state) {
        if (state.goal() == null || state.goal().isBlank()) {
            throw new IllegalArgumentException(
                    "cannot enter EXECUTING: goal is required"); //$NON-NLS-1$
        }
        if (state.tasks().isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot enter EXECUTING: tasks are required"); //$NON-NLS-1$
        }
        if (state.waves().isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot enter EXECUTING: waves are required"); //$NON-NLS-1$
        }
    }

    /**
     * Entry guard: VERIFYING requires all tasks DONE.
     */
    private static void validateVerifyingEntry(GsdState state) {
        boolean allDone = !state.tasks().isEmpty();
        for (GsdTask task : state.tasks()) {
            if (task.status() != GsdTaskStatus.DONE) {
                allDone = false;
                break;
            }
        }
        if (!allDone) {
            throw new IllegalArgumentException(
                    "cannot enter VERIFYING: all tasks must be DONE"); //$NON-NLS-1$
        }
    }

    // ---- Phase-gate helpers -----------------------------------------------

    private static void requirePhase(String operation, GsdPhase actual, GsdPhase... allowed) {
        for (GsdPhase p : allowed) {
            if (actual == p) {
                return;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(operation).append(" requires phase "); //$NON-NLS-1$
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) sb.append(" or "); //$NON-NLS-1$
            sb.append(allowed[i]);
        }
        sb.append(", but current phase is ").append(actual); //$NON-NLS-1$
        throw new IllegalStateException(sb.toString());
    }

    // ---- Get state --------------------------------------------------------

    /**
     * Reads the current GSD state for a project.
     *
     * @param projectRoot the project root path
     * @return the current state
     * @throws IOException on I/O error
     */
    public static GsdState getState(String projectRoot) throws IOException {
        return new GsdStateStore(projectRoot).load();
    }

    // ---- Record decision --------------------------------------------------

    /**
     * Records a decision in the current state.
     * <strong>Phase guard:</strong> DISCOVERY only.
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param id                decision id
     * @param summary           short summary
     * @param rationale         rationale
     * @param alternatives      alternatives considered
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalStateException     if not in DISCOVERY phase
     */
    public static GsdState recordDecision(String projectRoot, long expectedRevision,
            String id, String summary, String rationale, List<String> alternatives) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        requirePhase("recordDecision", current.phase(), GsdPhase.DISCOVERY); //$NON-NLS-1$
        GsdDecision decision = new GsdDecision(id, summary, rationale,
                alternatives != null ? alternatives : List.of());
        List<GsdDecision> decisions = new ArrayList<>(current.decisions());
        decisions.add(decision);
        GsdState next = new GsdState(
                current.schemaVersion(),
                current.revision(),
                current.phase(),
                current.goal(),
                decisions,
                current.tasks(),
                current.waves(),
                current.evidence(),
                current.sessionPointer());
        return store.save(next);
    }

    // ---- Create plan ------------------------------------------------------

    /**
     * Creates a plan: sets the goal, tasks, and waves. Does <em>not</em> change the phase;
     * phase transitions are performed exclusively by {@link #transitionPhase}.
     * <strong>Phase guard:</strong> PLANNING only.
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param goal              the project goal (non-blank)
     * @param tasks             list of tasks (non-empty)
     * @param waves             list of waves (non-empty)
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalStateException     if not in PLANNING phase
     * @throws IllegalArgumentException  if goal/tasks/waves are empty
     */
    public static GsdState createPlan(String projectRoot, long expectedRevision,
            String goal, List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        requirePhase("createPlan", current.phase(), GsdPhase.PLANNING); //$NON-NLS-1$
        Objects.requireNonNull(goal, "goal"); //$NON-NLS-1$
        Objects.requireNonNull(tasks, "tasks"); //$NON-NLS-1$
        Objects.requireNonNull(waves, "waves"); //$NON-NLS-1$
        if (goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank"); //$NON-NLS-1$
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty"); //$NON-NLS-1$
        }
        if (waves.isEmpty()) {
            throw new IllegalArgumentException("waves must not be empty"); //$NON-NLS-1$
        }

        GsdState next = new GsdState(
                current.schemaVersion(),
                current.revision(),
                current.phase(),
                goal,
                current.decisions(),
                tasks,
                waves,
                current.evidence(),
                current.sessionPointer());
        return store.save(next);
    }

    // ---- Update task ------------------------------------------------------

    /**
     * Updates a single task's status only. Title, wave, dependencies, and evidence
     * ids are preserved; plan changes go through {@link #createPlan}, evidence links
     * through {@link #recordEvidence}.
     * <strong>Phase guard:</strong> EXECUTING only.
     *
     * <p>If the new status is {@link GsdTaskStatus#IN_PROGRESS} or
     * {@link GsdTaskStatus#DONE}, all tasks listed in {@code dependsOn} must
     * already be {@link GsdTaskStatus#DONE}.</p>
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param taskId            the task id to update
     * @param newStatus         the new status (must not be null)
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalArgumentException  if the task is not found or dependency guard fails
     */
    public static GsdState updateTask(String projectRoot, long expectedRevision,
            String taskId, GsdTaskStatus newStatus) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        requirePhase("updateTask", current.phase(), GsdPhase.EXECUTING); //$NON-NLS-1$

        List<GsdTask> tasks = new ArrayList<>(current.tasks());
        int idx = -1;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(taskId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("task not found: " + taskId); //$NON-NLS-1$
        }

        GsdTask existing = tasks.get(idx);

        // Dependency guard: IN_PROGRESS / DONE requires all dependsOn tasks DONE.
        if (newStatus == GsdTaskStatus.IN_PROGRESS
                || newStatus == GsdTaskStatus.DONE) {
            for (String depId : existing.dependsOn()) {
                GsdTask dep = findTaskById(tasks, depId);
                if (dep == null) {
                    throw new IllegalArgumentException("task " + taskId //$NON-NLS-1$
                            + " depends on unknown task: " + depId); //$NON-NLS-1$
                }
                if (dep.status() != GsdTaskStatus.DONE) {
                    throw new IllegalArgumentException("task " + taskId //$NON-NLS-1$
                            + " cannot be " + newStatus
                            + ": dependency " + depId + " is " + dep.status()); //$NON-NLS-1$
                }
            }
        }

        GsdTask updated = new GsdTask(
                existing.id(),
                existing.title(),
                newStatus,
                existing.waveId(),
                existing.dependsOn(),
                existing.evidenceIds());
        tasks.set(idx, updated);

        GsdState next = new GsdState(
                current.schemaVersion(),
                current.revision(),
                current.phase(),
                current.goal(),
                current.decisions(),
                tasks,
                current.waves(),
                current.evidence(),
                current.sessionPointer());
        return store.save(next);
    }

    private static GsdTask findTaskById(List<GsdTask> tasks, String id) {
        for (GsdTask t : tasks) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    // ---- Record evidence --------------------------------------------------

    /**
     * Records a piece of evidence and links it to tasks.
     * <strong>Phase guard:</strong> EXECUTING or VERIFYING.
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param id                evidence id
     * @param description       what the evidence shows
     * @param provenance        how the evidence was obtained
     * @param taskIds           tasks this evidence supports
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalStateException     if not in EXECUTING or VERIFYING phase
     */
    public static GsdState recordEvidence(String projectRoot, long expectedRevision,
            String id, String description, GsdProvenance provenance, List<String> taskIds) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        requirePhase("recordEvidence", current.phase(), GsdPhase.EXECUTING, GsdPhase.VERIFYING); //$NON-NLS-1$

        Instant now = Instant.now();
        GsdEvidence evidence = new GsdEvidence(id, description, provenance,
                taskIds != null ? taskIds : List.of(), now);
        List<GsdEvidence> evidenceList = new ArrayList<>(current.evidence());
        evidenceList.add(evidence);

        // Also link evidence to tasks.
        List<GsdTask> tasks = new ArrayList<>(current.tasks());
        Set<String> linkedTaskIds = taskIds != null ? Set.copyOf(taskIds) : Set.of();
        for (int i = 0; i < tasks.size(); i++) {
            GsdTask task = tasks.get(i);
            if (linkedTaskIds.contains(task.id())) {
                List<String> evIds = new ArrayList<>(task.evidenceIds());
                evIds.add(id);
                tasks.set(i, new GsdTask(
                        task.id(), task.title(), task.status(), task.waveId(),
                        task.dependsOn(), evIds));
            }
        }

        GsdState next = new GsdState(
                current.schemaVersion(),
                current.revision(),
                current.phase(),
                current.goal(),
                current.decisions(),
                tasks,
                current.waves(),
                evidenceList,
                current.sessionPointer());
        return store.save(next);
    }

    // ---- Structured result builder ----------------------------------------

    /**
     * Builds a deterministic structured result for tool responses.
     *
     * @param success    whether the operation succeeded
     * @param operation  the operation name
     * @param revision   the new revision (0 on failure)
     * @param phase      the resulting phase
     * @param errorCode  the error code (null on success)
     * @return a JSON object suitable for ToolResult structured data
     */
    public static JsonObject buildResult(boolean success, String operation,
            long revision, GsdPhase phase, String errorCode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("status", success ? "success" : "error"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        obj.addProperty("operation", operation); //$NON-NLS-1$
        obj.addProperty("revision", revision); //$NON-NLS-1$
        if (phase != null) {
            obj.addProperty("phase", phase.name()); //$NON-NLS-1$
        } else {
            obj.addProperty("phase", ""); //$NON-NLS-1$
        }
        if (errorCode != null) {
            obj.addProperty("error_code", errorCode); //$NON-NLS-1$
        }
        return obj;
    }

    // ---- Error codes ------------------------------------------------------

    /** Error code: revision mismatch (stale state). */
    public static final String ERR_STALE = "stale"; //$NON-NLS-1$
    /** Error code: corrupt state file. */
    public static final String ERR_CORRUPT = "corrupt"; //$NON-NLS-1$
    /** Error code: guard/invariant violation. */
    public static final String ERR_GUARD = "guard"; //$NON-NLS-1$
    /** Error code: I/O failure. */
    public static final String ERR_IO = "io"; //$NON-NLS-1$
    /** Error code: invalid parameters / illegal transition. */
    public static final String ERR_INVALID = "invalid"; //$NON-NLS-1$
}
