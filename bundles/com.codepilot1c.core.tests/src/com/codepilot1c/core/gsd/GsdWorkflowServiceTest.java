/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.google.gson.JsonObject;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link GsdWorkflowService}: transitions, operations, and structured results.
 */
public class GsdWorkflowServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path projectRoot;

    @Before
    public void setUp() throws IOException {
        projectRoot = tmp.newFolder("project").toPath(); //$NON-NLS-1$
    }

    // ---- Transitions -----------------------------------------------------

    @Test
    public void forwardTransitionsAreLegal() {
        GsdWorkflowService.validateTransition(GsdPhase.DISCOVERY, GsdPhase.PLANNING, null);
        GsdWorkflowService.validateTransition(GsdPhase.PLANNING, GsdPhase.EXECUTING, null);
        GsdWorkflowService.validateTransition(GsdPhase.EXECUTING, GsdPhase.VERIFYING, null);
        GsdWorkflowService.validateTransition(GsdPhase.VERIFYING, GsdPhase.CLOSED, null);
    }

    @Test
    public void rollbackRequiresReason() {
        GsdWorkflowService.validateTransition(GsdPhase.VERIFYING, GsdPhase.EXECUTING, "tests failed"); //$NON-NLS-1$
    }

    @Test
    public void rollbackWithoutReasonIsRejected() {
        try {
            GsdWorkflowService.validateTransition(GsdPhase.VERIFYING, GsdPhase.EXECUTING, null);
            fail("expected exception"); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("reason")); //$NON-NLS-1$
        }
    }

    @Test
    public void rollbackWithBlankReasonIsRejected() {
        try {
            GsdWorkflowService.validateTransition(GsdPhase.VERIFYING, GsdPhase.EXECUTING, "   "); //$NON-NLS-1$
            fail("expected exception"); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("reason")); //$NON-NLS-1$
        }
    }

    @Test
    public void samePhaseIsRejected() {
        try {
            GsdWorkflowService.validateTransition(GsdPhase.DISCOVERY, GsdPhase.DISCOVERY, null);
            fail("expected exception"); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("already")); //$NON-NLS-1$
        }
    }

    @Test
    public void skipPhaseIsRejected() {
        try {
            GsdWorkflowService.validateTransition(GsdPhase.DISCOVERY, GsdPhase.EXECUTING, null);
            fail("expected exception"); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("illegal")); //$NON-NLS-1$
        }
    }

    @Test
    public void backwardTransitionOtherThanRollbackIsRejected() {
        try {
            GsdWorkflowService.validateTransition(GsdPhase.EXECUTING, GsdPhase.PLANNING, null);
            fail("expected exception"); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("illegal")); //$NON-NLS-1$
        }
    }

    // ---- State operations ------------------------------------------------

    @Test
    public void getStateReturnsFreshForNewProject() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        assertNotNull(state);
        assertEquals(GsdPhase.DISCOVERY, state.phase());
        assertEquals(GsdState.INITIAL_REVISION, state.revision());
    }

    @Test
    public void transitionPhasePersistsAndIncrementsRevision() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        long rev = state.revision();

        GsdState next = GsdWorkflowService.transitionPhase(
                projectRoot.toString(), rev, GsdPhase.PLANNING, null);
        assertEquals(rev + 1, next.revision());
        assertEquals(GsdPhase.PLANNING, next.phase());
    }

    @Test
    public void transitionPhaseWithRollbackWorks() throws IOException {
        // DISCOVERY -> PLANNING -> plan -> EXECUTING -> evidence+DONE -> VERIFYING -> rollback
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        long rev = state.revision();
        state = GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.PLANNING, null);
        rev = state.revision();
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdWave wave = new GsdWave("w1", "w", "g", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = GsdWorkflowService.createPlan(projectRoot.toString(), rev, "g", List.of(task), List.of(wave)); //$NON-NLS-1$
        rev = state.revision();
        state = GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.EXECUTING, null);
        rev = state.revision();
        state = GsdWorkflowService.recordEvidence(
                projectRoot.toString(), rev, "e1", "passed", GsdProvenance.TESTED, List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$
        rev = state.revision();
        state = GsdWorkflowService.updateTask(projectRoot.toString(), rev, "t1", GsdTaskStatus.DONE); //$NON-NLS-1$
        rev = state.revision();
        state = GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.VERIFYING, null);
        rev = state.revision();

        // Rollback VERIFYING -> EXECUTING with reason
        GsdState rolledBack = GsdWorkflowService.transitionPhase(
                projectRoot.toString(), rev, GsdPhase.EXECUTING, "need more work"); //$NON-NLS-1$
        assertEquals(GsdPhase.EXECUTING, rolledBack.phase());
        assertEquals(rev + 1, rolledBack.revision());
    }

    @Test
    public void staleRevisionThrowsOnTransition() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        long rev = state.revision();
        GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.PLANNING, null);
        try {
            GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.EXECUTING, null);
            fail("expected GsdStaleRevisionException"); //$NON-NLS-1$
        } catch (GsdStaleRevisionException e) {
            assertEquals(rev, e.getExpectedRevision());
        }
    }

    // ---- Phase-gated operations ------------------------------------------

    @Test
    public void recordDecisionOnlyInDiscovery() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        long rev = state.revision();
        // DISCOVERY: OK.
        GsdWorkflowService.recordDecision(
                projectRoot.toString(), rev, "d1", "use JSON", "source of truth", List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Transition to PLANNING.
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.PLANNING, null);
        // After transition to PLANNING: rejected.
        state = GsdWorkflowService.getState(projectRoot.toString());
        try {
            GsdWorkflowService.recordDecision(
                    projectRoot.toString(), state.revision(), "d2", "alt", "why", List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected IllegalStateException"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("DISCOVERY")); //$NON-NLS-1$
        }
    }

    @Test
    public void createPlanOnlyInPlanning() throws IOException {
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdWave wave = new GsdWave("w1", "wave 1", "sub-goal", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // In DISCOVERY: rejected.
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        try {
            GsdWorkflowService.createPlan(
                    projectRoot.toString(), state.revision(), "goal", List.of(task), List.of(wave)); //$NON-NLS-1$
            fail("expected IllegalStateException"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("PLANNING")); //$NON-NLS-1$
        }

        // Transition to PLANNING, then create plan: OK.
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.PLANNING, null);
        state = GsdWorkflowService.getState(projectRoot.toString());
        state = GsdWorkflowService.createPlan(
                projectRoot.toString(), state.revision(), "goal", List.of(task), List.of(wave)); //$NON-NLS-1$
        assertEquals("goal", state.goal()); //$NON-NLS-1$
        assertEquals(1, state.tasks().size());
        assertEquals(GsdPhase.PLANNING, state.phase());
    }

    @Test
    public void updateTaskOnlyInExecuting() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        try {
            GsdWorkflowService.updateTask(
                    projectRoot.toString(), state.revision(), "t1", GsdTaskStatus.DONE); //$NON-NLS-1$
            fail("expected IllegalStateException"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("EXECUTING")); //$NON-NLS-1$
        }
    }

    @Test
    public void recordEvidenceOnlyInExecutingOrVerifying() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        // DISCOVERY: rejected.
        try {
            GsdWorkflowService.recordEvidence(
                    projectRoot.toString(), state.revision(), "e1", "x", GsdProvenance.OBSERVED, List.of()); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected IllegalStateException"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("EXECUTING")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("VERIFYING")); //$NON-NLS-1$
        }
    }

    // ---- Rollback audit decision -----------------------------------------

    @Test
    public void rollbackRecordsAuditDecision() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        long rev = state.revision();
        state = GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.PLANNING, null);
        rev = state.revision();
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdWave wave = new GsdWave("w1", "w", "g", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = GsdWorkflowService.createPlan(projectRoot.toString(), rev, "g", List.of(task), List.of(wave)); //$NON-NLS-1$
        rev = state.revision();
        state = GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.EXECUTING, null);
        rev = state.revision();
        state = GsdWorkflowService.recordEvidence(
                projectRoot.toString(), rev, "e1", "ok", GsdProvenance.TESTED, List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$
        rev = state.revision();
        state = GsdWorkflowService.updateTask(projectRoot.toString(), rev, "t1", GsdTaskStatus.DONE); //$NON-NLS-1$
        rev = state.revision();
        state = GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.VERIFYING, null);
        rev = state.revision();

        GsdState rolledBack = GsdWorkflowService.transitionPhase(
                projectRoot.toString(), rev, GsdPhase.EXECUTING, "tests failed"); //$NON-NLS-1$
        // A rollback audit decision must exist.
        assertEquals(1, rolledBack.decisions().size());
        GsdDecision d = rolledBack.decisions().get(0);
        assertEquals("rollback-r" + rev, d.id()); //$NON-NLS-1$
        assertEquals("Verification rollback", d.summary()); //$NON-NLS-1$
        assertEquals("tests failed", d.rationale()); //$NON-NLS-1$
    }

    @Test
    public void forwardTransitionDoesNotRecordDecision() throws IOException {
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        long rev = state.revision();
        state = GsdWorkflowService.transitionPhase(projectRoot.toString(), rev, GsdPhase.PLANNING, null);
        // No decisions added.
        assertTrue(state.decisions().isEmpty());
    }

    // ---- Dependency guard ------------------------------------------------

    @Test
    public void updateTaskInProgressRequiresDependenciesDone() throws IOException {
        GsdTask depTask = new GsdTask("t-dep", "dep", GsdTaskStatus.PENDING, "w1", List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTask mainTask = new GsdTask("t1", "main", GsdTaskStatus.PENDING, "w1", List.of("t-dep"), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GsdWave wave = new GsdWave("w1", "w", "g", List.of("t-dep", "t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // PLANNING -> EXECUTING
        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.PLANNING, null);
        state = GsdWorkflowService.getState(projectRoot.toString());
        state = GsdWorkflowService.createPlan(
                projectRoot.toString(), state.revision(), "g", List.of(depTask, mainTask), List.of(wave)); //$NON-NLS-1$
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.EXECUTING, null);

        // Try to set t1 IN_PROGRESS while t-dep is still PENDING.
        state = GsdWorkflowService.getState(projectRoot.toString());
        try {
            GsdWorkflowService.updateTask(
                    projectRoot.toString(), state.revision(), "t1", GsdTaskStatus.IN_PROGRESS); //$NON-NLS-1$
            fail("expected IllegalStateException"); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("dependency")); //$NON-NLS-1$
        }

        // Record evidence for t-dep, then mark it DONE, then t1 IN_PROGRESS should succeed.
        state = GsdWorkflowService.getState(projectRoot.toString());
        state = GsdWorkflowService.recordEvidence(
                projectRoot.toString(), state.revision(), "e-dep", "ok", GsdProvenance.TESTED, List.of("t-dep")); //$NON-NLS-1$ //$NON-NLS-2$
        state = GsdWorkflowService.getState(projectRoot.toString());
        state = GsdWorkflowService.updateTask(
                projectRoot.toString(), state.revision(), "t-dep", GsdTaskStatus.DONE); //$NON-NLS-1$
        state = GsdWorkflowService.getState(projectRoot.toString());
        state = GsdWorkflowService.updateTask(
                projectRoot.toString(), state.revision(), "t1", GsdTaskStatus.IN_PROGRESS); //$NON-NLS-1$
        assertEquals(GsdTaskStatus.IN_PROGRESS, state.tasks().stream()
                .filter(t -> t.id().equals("t1")).findFirst().get().status()); //$NON-NLS-1$
    }

    // ---- Record evidence -------------------------------------------------

    @Test
    public void recordEvidenceAppendsAndLinks() throws IOException {
        // PLANNING -> createPlan -> EXECUTING
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdWave wave = new GsdWave("w1", "wave", "goal", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.PLANNING, null);
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.createPlan(projectRoot.toString(), state.revision(), "goal", List.of(task), List.of(wave)); //$NON-NLS-1$
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.EXECUTING, null);

        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdState next = GsdWorkflowService.recordEvidence(
                projectRoot.toString(), state.revision(), "e1", "test passed", GsdProvenance.TESTED, List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, next.evidence().size());
        assertEquals("e1", next.evidence().get(0).id()); //$NON-NLS-1$
        assertEquals(1, next.tasks().get(0).evidenceIds().size());
        assertEquals("e1", next.tasks().get(0).evidenceIds().get(0)); //$NON-NLS-1$
    }

    // ---- buildResult -----------------------------------------------------

    @Test
    public void buildResultSuccessHasCorrectFields() {
        JsonObject result = GsdWorkflowService.buildResult(true, "gsd_transition", 42, GsdPhase.EXECUTING, null); //$NON-NLS-1$
        assertEquals("success", result.get("status").getAsString()); //$NON-NLS-1$
        assertEquals("gsd_transition", result.get("operation").getAsString()); //$NON-NLS-1$
        assertEquals(42, result.get("revision").getAsLong()); //$NON-NLS-1$
        assertEquals("EXECUTING", result.get("phase").getAsString()); //$NON-NLS-1$
        assertFalse(result.has("error_code")); //$NON-NLS-1$
    }

    @Test
    public void buildResultErrorHasErrorCode() {
        JsonObject result = GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_STALE); //$NON-NLS-1$
        assertEquals("error", result.get("status").getAsString()); //$NON-NLS-1$
        assertEquals(GsdWorkflowService.ERR_STALE, result.get("error_code").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void errorCodesAreDefined() {
        assertNotNull(GsdWorkflowService.ERR_STALE);
        assertNotNull(GsdWorkflowService.ERR_CORRUPT);
        assertNotNull(GsdWorkflowService.ERR_GUARD);
        assertNotNull(GsdWorkflowService.ERR_IO);
        assertNotNull(GsdWorkflowService.ERR_INVALID);
    }

    // ---- No deadlock: execute can record evidence before marking DONE ----

    @Test
    public void noDeadlockEvidenceThenDoneInExecuting() throws IOException {
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdWave wave = new GsdWave("w1", "w", "g", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        GsdState state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.PLANNING, null);
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.createPlan(projectRoot.toString(), state.revision(), "g", List.of(task), List.of(wave)); //$NON-NLS-1$
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.transitionPhase(projectRoot.toString(), state.revision(), GsdPhase.EXECUTING, null);

        // In EXECUTING: record evidence first, then mark DONE.
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.recordEvidence(
                projectRoot.toString(), state.revision(), "e1", "passed", GsdProvenance.TESTED, List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$
        state = GsdWorkflowService.getState(projectRoot.toString());
        GsdWorkflowService.updateTask(
                projectRoot.toString(), state.revision(), "t1", GsdTaskStatus.DONE); //$NON-NLS-1$
        // All tasks DONE, now can transition to VERIFYING.
        state = GsdWorkflowService.getState(projectRoot.toString());
        state = GsdWorkflowService.transitionPhase(
                projectRoot.toString(), state.revision(), GsdPhase.VERIFYING, null);
        assertEquals(GsdPhase.VERIFYING, state.phase());
    }
}
