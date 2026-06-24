package com.codepilot1c.core.agent.gsd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.agent.gsd.PlanArtifact.PlanTask;
import com.codepilot1c.core.agent.gsd.PlanArtifact.TaskStatus;

public class PlanArtifactResumeTest {

    @Test
    public void planWithGoalIsResumable() {
        PlanArtifact a = new PlanArtifact("s", "/p", "Создать справочник"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        a.setPhase(TaskPhase.EXECUTE);
        assertTrue(a.isResumable());
    }

    @Test
    public void planWithTasksButNoGoalIsResumable() {
        PlanArtifact a = new PlanArtifact("s", "/p", null); //$NON-NLS-1$ //$NON-NLS-2$
        a.getTasks().add(new PlanTask("t1", "do")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(a.isResumable());
    }

    @Test
    public void donePhaseIsNotResumable() {
        PlanArtifact a = new PlanArtifact("s", "/p", "цель"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        a.setPhase(TaskPhase.DONE);
        assertFalse(a.isResumable());
    }

    @Test
    public void doneStatusIsNotResumable() {
        PlanArtifact a = new PlanArtifact("s", "/p", "цель"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        a.setPhase(TaskPhase.VERIFY);
        a.setStatus("done"); //$NON-NLS-1$
        assertFalse(a.isResumable());
    }

    @Test
    public void emptyPlanIsNotResumable() {
        PlanArtifact a = new PlanArtifact("s", "/p", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(a.isResumable());
    }

    @Test
    public void resumeSummaryReportsPhaseGoalAndProgress() {
        PlanArtifact a = new PlanArtifact("s", "/p", "Создать справочник Товары"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        a.setPhase(TaskPhase.EXECUTE);
        PlanTask t1 = new PlanTask("t1", "создать"); //$NON-NLS-1$ //$NON-NLS-2$
        t1.setStatus(TaskStatus.DONE);
        PlanTask t2 = new PlanTask("t2", "форма"); //$NON-NLS-1$ //$NON-NLS-2$
        a.getTasks().add(t1);
        a.getTasks().add(t2);

        String summary = a.resumeSummary();
        assertTrue(summary.contains("EXECUTE")); //$NON-NLS-1$
        assertTrue(summary.contains("Создать справочник Товары")); //$NON-NLS-1$
        assertTrue(summary.contains("1/2")); //$NON-NLS-1$
        assertTrue(summary.contains("gsd_plan status")); //$NON-NLS-1$
    }
}
