package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for GSD execute-prompt content.
 *
 * <p>Guards against re-introducing the incorrect instruction that told the
 * model to transition to VERIFYING when a task is blocked — the VERIFYING
 * entry guard requires all tasks DONE, so a blocked unfinished task would
 * be rejected. The correct behaviour is to keep the task non-DONE, report
 * the blocker via evidence, and let the user decide.</p>
 *
 * <p>The only documented rollback is VERIFYING &rarr; EXECUTING via
 * gsd_transition with a reason.</p>
 */
public class GsdExecutePromptTest {

    @Test
    public void executePromptDoesNotInstructVerifyTransitionForBlockedTask() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        // The old incorrect text instructed transitioning to VERIFYING for blocked tasks.
        assertFalse(
                "Execute prompt must NOT instruct VERIFYING transition for blocked tasks " //$NON-NLS-1$
                        + "(VERIFYING entry guard requires all DONE)", //$NON-NLS-1$
                prompt.contains("переходи в VERIFYING через gsd_transition для оценки")); //$NON-NLS-1$
    }

    @Test
    public void executePromptInstructsKeepTaskNonDoneOnBlocker() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(
                "Execute prompt must instruct keeping the task in current (non-DONE) status on blocker", //$NON-NLS-1$
                prompt.contains("оставь задачу в текущем статусе")); //$NON-NLS-1$
        assertTrue(
                "Execute prompt must instruct recording the blocker via evidence", //$NON-NLS-1$
                prompt.contains("gsd_record_evidence")); //$NON-NLS-1$
    }

    @Test
    public void executePromptDocumentsOnlyRollbackIsVerifyingToExecuting() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(
                "Execute prompt must document that the only valid rollback is VERIFYING->EXECUTING", //$NON-NLS-1$
                prompt.contains("VERIFYING->EXECUTING")); //$NON-NLS-1$
    }

    @Test
    public void executePromptWarnsVerifyingRequiresAllDone() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(
                "Execute prompt must warn that VERIFYING transition requires all tasks DONE", //$NON-NLS-1$
                prompt.contains("all DONE")); //$NON-NLS-1$
    }

    @Test
    public void executePromptForbidsExecutingToPlanning() {
        String prompt = AgentPromptTemplates.buildGsdExecutePrompt();

        assertTrue(
                "Execute prompt must state EXECUTING->PLANNING is forbidden", //$NON-NLS-1$
                prompt.contains("EXECUTING->PLANNING")); //$NON-NLS-1$
    }
}
