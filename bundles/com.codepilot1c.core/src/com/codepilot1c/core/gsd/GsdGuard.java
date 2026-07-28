/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforces the structural invariants of a {@link GsdState}. Invariants are checked by
 * {@link GsdStateStore} before any write reaches disk, so a state that violates them
 * can never be persisted — the guard is mandatory, not an optional helper.
 *
 * <p>The defining guard is the provenance rule: a task may be marked {@link GsdTaskStatus#DONE}
 * only if it is backed by at least one piece of evidence whose {@link GsdProvenance} is
 * not {@link GsdProvenance#INFERRED}, and that evidence must reference the task back
 * (bidirectional consistency). Likewise, a {@link GsdPhase#CLOSED} phase requires every
 * task to be {@link GsdTaskStatus#DONE}, so closure on INFERRED-only evidence is impossible.</p>
 */
public final class GsdGuard {

    private GsdGuard() {
    }

    /**
     * Validates the full invariant set, throwing {@link GsdGuardException} with every
     * discovered violation if any invariant fails.
     *
     * @param state the state to validate (may be {@code null})
     * @throws GsdGuardException if any invariant is violated
     */
    public static void validate(GsdState state) {
        if (state == null) {
            throw new GsdGuardException("state must not be null"); //$NON-NLS-1$
        }
        List<String> violations = new ArrayList<>();
        checkSchema(state, violations);
        checkIdUniqueness(state, violations);
        checkReferences(state, violations);
        checkProvenanceGuard(state, violations);
        if (!violations.isEmpty()) {
            throw new GsdGuardException("GSD state invariants violated", violations); //$NON-NLS-1$
        }
    }

    /**
     * Returns {@code true} if the state passes all invariants without throwing.
     *
     * @param state the state to validate
     * @return {@code true} if valid
     */
    public static boolean isValid(GsdState state) {
        try {
            validate(state);
            return true;
        } catch (GsdGuardException e) {
            return false;
        }
    }

    private static void checkSchema(GsdState state, List<String> violations) {
        if (state.schemaVersion() != GsdState.CURRENT_SCHEMA_VERSION) {
            violations.add("schemaVersion " + state.schemaVersion() //$NON-NLS-1$
                    + " does not match current " + GsdState.CURRENT_SCHEMA_VERSION); //$NON-NLS-1$
        }
        if (state.revision() < GsdState.INITIAL_REVISION) {
            violations.add("revision " + state.revision() + " is negative"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (state.phase() == null) {
            violations.add("phase must not be null"); //$NON-NLS-1$
        }
        if (state.sessionPointer() == null) {
            violations.add("sessionPointer must not be null"); //$NON-NLS-1$
        }
    }

    private static void checkIdUniqueness(GsdState state, List<String> violations) {
        checkUniqueIds("decision", state.decisions().stream().map(GsdDecision::id).iterator(), violations); //$NON-NLS-1$
        checkUniqueIds("task", state.tasks().stream().map(GsdTask::id).iterator(), violations); //$NON-NLS-1$
        checkUniqueIds("wave", state.waves().stream().map(GsdWave::id).iterator(), violations); //$NON-NLS-1$
        checkUniqueIds("evidence", state.evidence().stream().map(GsdEvidence::id).iterator(), violations); //$NON-NLS-1$
    }

    private static void checkUniqueIds(String kind, java.util.Iterator<String> ids, List<String> violations) {
        Set<String> seen = new HashSet<>();
        while (ids.hasNext()) {
            String id = ids.next();
            if (id == null || id.isBlank()) {
                violations.add(kind + " id must be non-blank"); //$NON-NLS-1$
            } else if (!seen.add(id)) {
                violations.add("duplicate " + kind + " id: " + id); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private static void checkReferences(GsdState state, List<String> violations) {
        Set<String> taskIds = index(state.tasks(), GsdTask::id);
        Set<String> waveIds = index(state.waves(), GsdWave::id);
        Set<String> evidenceIds = index(state.evidence(), GsdEvidence::id);

        for (GsdTask task : state.tasks()) {
            String waveId = task.waveId();
            if (waveId != null && !waveId.isBlank() && !waveIds.contains(waveId)) {
                violations.add("task " + task.id() + " references unknown wave: " + waveId); //$NON-NLS-1$ //$NON-NLS-2$
            }
            for (String dep : task.dependsOn()) {
                if (!taskIds.contains(dep)) {
                    violations.add("task " + task.id() + " depends on unknown task: " + dep); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            for (String evId : task.evidenceIds()) {
                if (!evidenceIds.contains(evId)) {
                    violations.add("task " + task.id() + " references unknown evidence: " + evId); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        for (GsdWave wave : state.waves()) {
            for (String tid : wave.taskIds()) {
                if (!taskIds.contains(tid)) {
                    violations.add("wave " + wave.id() + " references unknown task: " + tid); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        for (GsdEvidence evidence : state.evidence()) {
            for (String tid : evidence.taskIds()) {
                if (!taskIds.contains(tid)) {
                    violations.add("evidence " + evidence.id() + " references unknown task: " + tid); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    private static void checkProvenanceGuard(GsdState state, List<String> violations) {
        // Index evidence by id for lookup.
        java.util.Map<String, GsdEvidence> byId = new java.util.HashMap<>();
        for (GsdEvidence evidence : state.evidence()) {
            byId.put(evidence.id(), evidence);
        }
        boolean allDone = !state.tasks().isEmpty();
        for (GsdTask task : state.tasks()) {
            if (task.status() == GsdTaskStatus.DONE) {
                boolean hasClosable = false;
                for (String evId : task.evidenceIds()) {
                    GsdEvidence evidence = byId.get(evId);
                    if (evidence == null) {
                        continue; // already reported as a dangling reference
                    }
                    if (!evidence.taskIds().contains(task.id())) {
                        violations.add("evidence " + evidence.id() //$NON-NLS-1$
                                + " is referenced by task " + task.id() //$NON-NLS-1$
                                + " but does not list it in taskIds"); //$NON-NLS-1$
                        continue;
                    }
                    if (evidence.provenance().isClosable()) {
                        hasClosable = true;
                    }
                }
                if (!hasClosable) {
                    violations.add("task " + task.id() //$NON-NLS-1$
                            + " is DONE but has no closable (non-INFERRED) evidence"); //$NON-NLS-1$
                }
            } else {
                allDone = false;
            }
        }
        if (state.phase() == GsdPhase.CLOSED) {
            if (state.tasks().isEmpty()) {
                violations.add("phase is CLOSED but there are no tasks"); //$NON-NLS-1$
            } else if (!allDone) {
                violations.add("phase is CLOSED but not all tasks are DONE"); //$NON-NLS-1$
            }
        }
    }

    private static <T> Set<String> index(List<T> items, java.util.function.Function<T, String> key) {
        Set<String> ids = new HashSet<>();
        for (T item : items) {
            ids.add(key.apply(item));
        }
        return ids;
    }
}