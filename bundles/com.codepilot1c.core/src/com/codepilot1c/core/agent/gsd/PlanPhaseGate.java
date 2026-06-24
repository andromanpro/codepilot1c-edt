/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.gsd;

import java.util.ArrayList;
import java.util.List;

import com.codepilot1c.core.agent.gsd.PlanArtifact.PlanTask;
import com.codepilot1c.core.agent.gsd.PlanArtifact.TaskStatus;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerificationItem;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerifyResult;

/**
 * Gate that enforces GSD phase transitions on a {@link PlanArtifact}.
 *
 * <p>Implements the GSD gate taxonomy for the lifecycle DISCUSS→PLAN→EXECUTE→
 * VERIFY→DONE: pre-flight (preconditions per target phase), PLAN-revision
 * (every task needs acceptance criteria), and goal-backward verification (DONE
 * requires recorded verification, all PASS). Denials carry actionable feedback
 * so the model can fix and retry (revision loop) rather than skip ahead.</p>
 *
 * <p>Pure logic, no I/O — unit-testable.</p>
 */
public final class PlanPhaseGate {

    private PlanPhaseGate() {
    }

    /**
     * Outcome of a transition check.
     *
     * @param allowed  whether the transition is permitted
     * @param feedback actionable reason when denied (null when allowed)
     */
    public record GateResult(boolean allowed, String feedback) {

        static GateResult ok() {
            return new GateResult(true, null);
        }

        static GateResult deny(String feedback) {
            return new GateResult(false, feedback);
        }
    }

    /**
     * Evaluates whether {@code artifact} may advance to {@code target}.
     *
     * @param artifact the current plan artifact
     * @param target   the desired next phase
     * @return the gate result
     */
    public static GateResult evaluate(PlanArtifact artifact, TaskPhase target) {
        if (artifact == null) {
            return GateResult.deny("Нет активного плана."); //$NON-NLS-1$
        }
        if (target == null) {
            return GateResult.deny("Не указана целевая фаза."); //$NON-NLS-1$
        }
        TaskPhase current = artifact.getPhase();
        if (target == current) {
            return GateResult.ok(); // idempotent
        }

        switch (target) {
            case DISCUSS:
                return GateResult.ok(); // allow returning to discuss (re-scope)
            case PLAN:
                if (current != TaskPhase.DISCUSS && current != TaskPhase.VERIFY) {
                    return GateResult.deny("PLAN достижим из DISCUSS (или из VERIFY для закрытия пробелов)."); //$NON-NLS-1$
                }
                if (isBlank(artifact.getGoal())) {
                    return GateResult.deny("Сначала задайте цель (set_goal) — она нужна для goal-backward проверки."); //$NON-NLS-1$
                }
                return GateResult.ok();
            case EXECUTE:
                if (current != TaskPhase.PLAN && current != TaskPhase.VERIFY) {
                    return GateResult.deny("EXECUTE достижим из PLAN (или из VERIFY для доисполнения)."); //$NON-NLS-1$
                }
                if (artifact.getTasks().isEmpty()) {
                    return GateResult.deny("Нет задач. Добавьте задачи (add_task) перед EXECUTE."); //$NON-NLS-1$
                }
                List<String> noAcceptance = new ArrayList<>();
                for (PlanTask task : artifact.getTasks()) {
                    if (task.getAcceptance().isEmpty()) {
                        noAcceptance.add(idOf(task));
                    }
                }
                if (!noAcceptance.isEmpty()) {
                    return GateResult.deny("PLAN-revision: у задач нет критериев приёмки: " + noAcceptance //$NON-NLS-1$
                            + ". Добавьте acceptance каждой задаче."); //$NON-NLS-1$
                }
                return GateResult.ok();
            case VERIFY:
                if (current != TaskPhase.EXECUTE) {
                    return GateResult.deny("VERIFY достижим из EXECUTE."); //$NON-NLS-1$
                }
                List<String> open = new ArrayList<>();
                for (PlanTask task : artifact.getTasks()) {
                    if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.IN_PROGRESS) {
                        open.add(idOf(task));
                    }
                }
                if (!open.isEmpty()) {
                    return GateResult.deny("Не завершены задачи: " + open //$NON-NLS-1$
                            + ". Обновите статус (update_task) до DONE/FAILED."); //$NON-NLS-1$
                }
                return GateResult.ok();
            case DONE:
                if (current != TaskPhase.VERIFY) {
                    return GateResult.deny("DONE достижим из VERIFY."); //$NON-NLS-1$
                }
                if (artifact.getVerification().isEmpty()) {
                    return GateResult.deny("Goal-backward: запишите проверку цели (record_verification) перед DONE."); //$NON-NLS-1$
                }
                List<String> notPassed = new ArrayList<>();
                List<String> noEvidence = new ArrayList<>();
                for (VerificationItem item : artifact.getVerification()) {
                    if (item.getResult() != VerifyResult.PASS) {
                        notPassed.add(item.getCriterion());
                    } else if (isBlank(item.getEvidence())) {
                        // Goal-backward: a PASS must be backed by evidence (e.g. get_diagnostics output),
                        // not an unsupported claim ("existence ≠ implementation").
                        noEvidence.add(item.getCriterion());
                    }
                }
                if (!notPassed.isEmpty()) {
                    return GateResult.deny("Проверки не PASS: " + notPassed //$NON-NLS-1$
                            + ". Вернитесь в PLAN/EXECUTE и закройте пробелы."); //$NON-NLS-1$
                }
                if (!noEvidence.isEmpty()) {
                    return GateResult.deny("Нет доказательств (evidence) у проверок: " + noEvidence //$NON-NLS-1$
                            + ". Запишите результат get_diagnostics/qa как evidence."); //$NON-NLS-1$
                }
                return GateResult.ok();
            default:
                return GateResult.deny("Неизвестная фаза: " + target); //$NON-NLS-1$
        }
    }

    private static String idOf(PlanTask task) {
        return task.getId() != null && !task.getId().isBlank() ? task.getId() : task.getDescription();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
