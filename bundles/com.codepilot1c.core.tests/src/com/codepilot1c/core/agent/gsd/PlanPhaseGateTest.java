package com.codepilot1c.core.agent.gsd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.agent.gsd.PlanArtifact.PlanTask;
import com.codepilot1c.core.agent.gsd.PlanArtifact.TaskStatus;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerificationItem;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerifyResult;
import com.codepilot1c.core.agent.gsd.PlanPhaseGate.GateResult;

public class PlanPhaseGateTest {

    private PlanArtifact artifact(TaskPhase phase, String goal) {
        PlanArtifact a = new PlanArtifact("s", "/p", goal); //$NON-NLS-1$ //$NON-NLS-2$
        a.setPhase(phase);
        return a;
    }

    private PlanTask taskWithAcceptance(String id, TaskStatus status) {
        PlanTask t = new PlanTask(id, "do " + id); //$NON-NLS-1$
        t.getAcceptance().add("works"); //$NON-NLS-1$
        t.setStatus(status);
        return t;
    }

    @Test
    public void planRequiresGoal() {
        assertFalse(PlanPhaseGate.evaluate(artifact(TaskPhase.DISCUSS, null), TaskPhase.PLAN).allowed());
        assertTrue(PlanPhaseGate.evaluate(artifact(TaskPhase.DISCUSS, "цель"), TaskPhase.PLAN).allowed()); //$NON-NLS-1$
    }

    @Test
    public void executeRequiresTasksWithAcceptance() {
        PlanArtifact a = artifact(TaskPhase.PLAN, "цель"); //$NON-NLS-1$
        assertFalse(PlanPhaseGate.evaluate(a, TaskPhase.EXECUTE).allowed()); // no tasks

        PlanTask noAcc = new PlanTask("t1", "x"); //$NON-NLS-1$ //$NON-NLS-2$
        a.getTasks().add(noAcc);
        GateResult r = PlanPhaseGate.evaluate(a, TaskPhase.EXECUTE);
        assertFalse(r.allowed()); // missing acceptance
        assertTrue(r.feedback().contains("acceptance")); //$NON-NLS-1$

        noAcc.getAcceptance().add("ok"); //$NON-NLS-1$
        assertTrue(PlanPhaseGate.evaluate(a, TaskPhase.EXECUTE).allowed());
    }

    @Test
    public void verifyRequiresAllTasksFinished() {
        PlanArtifact a = artifact(TaskPhase.EXECUTE, "цель"); //$NON-NLS-1$
        a.getTasks().add(taskWithAcceptance("t1", TaskStatus.DONE)); //$NON-NLS-1$
        a.getTasks().add(taskWithAcceptance("t2", TaskStatus.IN_PROGRESS)); //$NON-NLS-1$
        assertFalse(PlanPhaseGate.evaluate(a, TaskPhase.VERIFY).allowed());

        a.getTasks().get(1).setStatus(TaskStatus.DONE);
        assertTrue(PlanPhaseGate.evaluate(a, TaskPhase.VERIFY).allowed());
    }

    @Test
    public void doneRequiresAllVerificationPass() {
        PlanArtifact a = artifact(TaskPhase.VERIFY, "цель"); //$NON-NLS-1$
        assertFalse(PlanPhaseGate.evaluate(a, TaskPhase.DONE).allowed()); // no verification

        VerificationItem v = new VerificationItem("crit", "get_diagnostics"); //$NON-NLS-1$ //$NON-NLS-2$
        v.setResult(VerifyResult.FAIL);
        a.getVerification().add(v);
        assertFalse(PlanPhaseGate.evaluate(a, TaskPhase.DONE).allowed()); // FAIL present

        v.setResult(VerifyResult.PASS);
        v.setEvidence("0 errors"); //$NON-NLS-1$
        assertTrue(PlanPhaseGate.evaluate(a, TaskPhase.DONE).allowed());
    }

    @Test
    public void doneRequiresEvidenceForPassedVerification() {
        PlanArtifact a = artifact(TaskPhase.VERIFY, "цель"); //$NON-NLS-1$
        VerificationItem v = new VerificationItem("crit", "get_diagnostics"); //$NON-NLS-1$ //$NON-NLS-2$
        v.setResult(VerifyResult.PASS); // PASS but no evidence
        a.getVerification().add(v);

        GateResult r = PlanPhaseGate.evaluate(a, TaskPhase.DONE);
        assertFalse(r.allowed());
        assertTrue(r.feedback().contains("evidence")); //$NON-NLS-1$

        v.setEvidence("get_diagnostics: 0 errors"); //$NON-NLS-1$
        assertTrue(PlanPhaseGate.evaluate(a, TaskPhase.DONE).allowed());
    }

    @Test
    public void cannotSkipPhases() {
        // DISCUSS straight to EXECUTE is not allowed
        assertFalse(PlanPhaseGate.evaluate(artifact(TaskPhase.DISCUSS, "цель"), TaskPhase.EXECUTE).allowed()); //$NON-NLS-1$
        // DISCUSS straight to DONE is not allowed
        assertFalse(PlanPhaseGate.evaluate(artifact(TaskPhase.DISCUSS, "цель"), TaskPhase.DONE).allowed()); //$NON-NLS-1$
    }

    @Test
    public void sameePhaseIsIdempotentlyAllowed() {
        assertTrue(PlanPhaseGate.evaluate(artifact(TaskPhase.PLAN, "цель"), TaskPhase.PLAN).allowed()); //$NON-NLS-1$
    }

    @Test
    public void verifyCanReturnToExecuteForGapClosure() {
        PlanArtifact a = artifact(TaskPhase.VERIFY, "цель"); //$NON-NLS-1$
        a.getTasks().add(taskWithAcceptance("t1", TaskStatus.DONE)); //$NON-NLS-1$
        assertTrue(PlanPhaseGate.evaluate(a, TaskPhase.EXECUTE).allowed());
    }
}
