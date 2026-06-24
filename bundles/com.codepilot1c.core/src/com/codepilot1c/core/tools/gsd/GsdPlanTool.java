/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.codepilot1c.core.agent.gsd.PlanArtifact;
import com.codepilot1c.core.agent.gsd.PlanArtifact.Checkpoint;
import com.codepilot1c.core.agent.gsd.PlanArtifact.Decision;
import com.codepilot1c.core.agent.gsd.PlanArtifact.PlanTask;
import com.codepilot1c.core.agent.gsd.PlanArtifact.TaskStatus;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerificationItem;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerifyResult;
import com.codepilot1c.core.agent.gsd.PlanArtifactStore;
import com.codepilot1c.core.agent.gsd.PlanPhaseGate;
import com.codepilot1c.core.agent.gsd.TaskPhase;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * GSD plan-state tool: drives the persistent {@link PlanArtifact} through the
 * DISCUSS→PLAN→EXECUTE→VERIFY lifecycle. Used only in GSD mode (the phase
 * protocol is injected into the system prompt then).
 *
 * <p>Phase transitions go through {@link PlanPhaseGate}, so the model cannot skip
 * ahead (e.g. enter EXECUTE without tasks+acceptance, or DONE without passing
 * goal-backward verification).</p>
 */
@ToolMeta(name = "gsd_plan", category = "general", mutating = false, tags = {"workspace"})
public class GsdPlanTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "enum": ["status", "set_goal", "add_decision", "add_task", "update_task", "record_verification", "advance_phase"],
                  "description": "GSD plan operation."
                },
                "goal": {"type": "string", "description": "Goal statement (set_goal)."},
                "question": {"type": "string", "description": "Clarification asked (add_decision)."},
                "answer": {"type": "string", "description": "User decision (add_decision)."},
                "task_id": {"type": "string", "description": "Task id (add_task/update_task)."},
                "description": {"type": "string", "description": "Task description (add_task)."},
                "files": {"type": "string", "description": "Affected files/objects, comma- or newline-separated (add_task)."},
                "acceptance": {"type": "string", "description": "Acceptance criteria, comma- or newline-separated (add_task)."},
                "wave": {"type": "integer", "description": "Optional wave index for parallelizable tasks (add_task)."},
                "status": {"type": "string", "enum": ["PENDING", "IN_PROGRESS", "DONE", "FAILED"], "description": "Task status (update_task)."},
                "criterion": {"type": "string", "description": "Verification criterion (record_verification)."},
                "method": {"type": "string", "description": "How it was verified (record_verification)."},
                "result": {"type": "string", "enum": ["PENDING", "PASS", "FAIL"], "description": "Verification result (record_verification)."},
                "evidence": {"type": "string", "description": "Evidence/output (record_verification)."},
                "phase": {"type": "string", "enum": ["DISCUSS", "PLAN", "EXECUTE", "VERIFY", "DONE"], "description": "Target phase (advance_phase)."}
              },
              "required": ["command"],
              "additionalProperties": true
            }
            """; //$NON-NLS-1$

    private final PlanArtifactStore store;

    public GsdPlanTool() {
        this(new PlanArtifactStore());
    }

    GsdPlanTool(PlanArtifactStore store) {
        this.store = store;
    }

    @Override
    public String getDescription() {
        return "Управление планом GSD-режима: цель, решения, задачи, проверки и переходы фаз DISCUSS→PLAN→EXECUTE→VERIFY (с гейтами)."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String command = params.optString("command", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (command.isBlank()) {
                return ToolResult.failure("Параметр command обязателен."); //$NON-NLS-1$
            }

            Session session = resolveSession();
            if (session == null) {
                return ToolResult.failure("Нет активной сессии для GSD-плана."); //$NON-NLS-1$
            }
            String sessionId = session.getId();
            PlanArtifact artifact = store.load(sessionId).orElseGet(() -> new PlanArtifact(
                    sessionId, session.getProjectPath(), null));

            try {
                return switch (command.toLowerCase(Locale.ROOT)) {
                    case "status" -> ToolResult.success(renderStatus(artifact)); //$NON-NLS-1$
                    case "set_goal" -> setGoal(artifact, params); //$NON-NLS-1$
                    case "add_decision" -> addDecision(artifact, params); //$NON-NLS-1$
                    case "add_task" -> addTask(artifact, params); //$NON-NLS-1$
                    case "update_task" -> updateTask(artifact, params); //$NON-NLS-1$
                    case "record_verification" -> recordVerification(artifact, params); //$NON-NLS-1$
                    case "advance_phase" -> advancePhase(artifact, params); //$NON-NLS-1$
                    default -> ToolResult.failure("Неизвестная команда: " + command); //$NON-NLS-1$
                };
            } catch (RuntimeException e) {
                return ToolResult.failure("Ошибка gsd_plan: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult setGoal(PlanArtifact artifact, ToolParameters params) {
        String goal = params.optString("goal", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (goal.isBlank()) {
            return ToolResult.failure("set_goal требует goal."); //$NON-NLS-1$
        }
        artifact.setGoal(goal.trim());
        store.save(artifact);
        return ToolResult.success("Цель зафиксирована. Фаза: " + artifact.getPhase() //$NON-NLS-1$
                + ". Дальше — add_task для декомпозиции."); //$NON-NLS-1$
    }

    private ToolResult addDecision(PlanArtifact artifact, ToolParameters params) {
        String question = params.optString("question", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = params.optString("answer", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (answer.isBlank()) {
            return ToolResult.failure("add_decision требует answer."); //$NON-NLS-1$
        }
        artifact.getDecisions().add(new Decision(question.trim(), answer.trim()));
        store.save(artifact);
        return ToolResult.success("Решение зафиксировано (всего: " + artifact.getDecisions().size() + ")."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private ToolResult addTask(PlanArtifact artifact, ToolParameters params) {
        String id = params.optString("task_id", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String description = params.optString("description", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (description.isBlank()) {
            return ToolResult.failure("add_task требует description."); //$NON-NLS-1$
        }
        if (id.isBlank()) {
            id = "t" + (artifact.getTasks().size() + 1); //$NON-NLS-1$
        }
        PlanTask task = new PlanTask(id.trim(), description.trim());
        task.getFiles().addAll(splitList(params.optString("files", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        task.getAcceptance().addAll(splitList(params.optString("acceptance", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        task.setToolHint(emptyToNull(params.optString("tool_hint", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        task.setWave(params.optInt("wave", 0)); //$NON-NLS-1$
        artifact.getTasks().add(task);
        store.save(artifact);
        return ToolResult.success("Задача " + task.getId() + " добавлена (всего: " //$NON-NLS-1$ //$NON-NLS-2$
                + artifact.getTasks().size() + "). Не забудь acceptance перед EXECUTE."); //$NON-NLS-1$
    }

    private ToolResult updateTask(PlanArtifact artifact, ToolParameters params) {
        String id = params.optString("task_id", ""); //$NON-NLS-1$ //$NON-NLS-2$
        PlanTask task = artifact.getTasks().stream()
                .filter(t -> id.equals(t.getId()))
                .findFirst().orElse(null);
        if (task == null) {
            return ToolResult.failure("Задача не найдена: " + id); //$NON-NLS-1$
        }
        TaskStatus status = parseEnum(TaskStatus.class, params.optString("status", "")); //$NON-NLS-1$ //$NON-NLS-2$
        if (status == null) {
            return ToolResult.failure("update_task требует валидный status (PENDING/IN_PROGRESS/DONE/FAILED)."); //$NON-NLS-1$
        }
        task.setStatus(status);
        if (status == TaskStatus.DONE || status == TaskStatus.FAILED) {
            artifact.setCheckpoint(new Checkpoint(task.getId(), "next task")); //$NON-NLS-1$
        }
        store.save(artifact);
        return ToolResult.success("Задача " + id + " → " + status + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private ToolResult recordVerification(PlanArtifact artifact, ToolParameters params) {
        String criterion = params.optString("criterion", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (criterion.isBlank()) {
            return ToolResult.failure("record_verification требует criterion."); //$NON-NLS-1$
        }
        VerifyResult result = parseEnum(VerifyResult.class, params.optString("result", "")); //$NON-NLS-1$ //$NON-NLS-2$
        VerificationItem item = new VerificationItem(criterion.trim(), params.optString("method", "")); //$NON-NLS-1$ //$NON-NLS-2$
        item.setResult(result != null ? result : VerifyResult.PENDING);
        item.setEvidence(emptyToNull(params.optString("evidence", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        artifact.getVerification().add(item);
        store.save(artifact);
        return ToolResult.success("Проверка зафиксирована: " + criterion + " = " + item.getResult() + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private ToolResult advancePhase(PlanArtifact artifact, ToolParameters params) {
        TaskPhase target = parseEnum(TaskPhase.class, params.optString("phase", "")); //$NON-NLS-1$ //$NON-NLS-2$
        if (target == null) {
            return ToolResult.failure("advance_phase требует валидную phase (DISCUSS/PLAN/EXECUTE/VERIFY/DONE)."); //$NON-NLS-1$
        }
        PlanPhaseGate.GateResult gate = PlanPhaseGate.evaluate(artifact, target);
        if (!gate.allowed()) {
            return ToolResult.failure("Переход в " + target + " заблокирован гейтом: " + gate.feedback()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        artifact.setPhase(target);
        if (target == TaskPhase.DONE) {
            artifact.setStatus("done"); //$NON-NLS-1$
        }
        store.save(artifact);
        return ToolResult.success("Фаза → " + target + ".\n\n" + renderStatus(artifact)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String renderStatus(PlanArtifact artifact) {
        StringBuilder sb = new StringBuilder();
        sb.append("## GSD план\n"); //$NON-NLS-1$
        sb.append("Фаза: ").append(artifact.getPhase()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("Цель: ").append(artifact.getGoal() != null ? artifact.getGoal() : "(не задана)").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!artifact.getDecisions().isEmpty()) {
            sb.append("Решения: ").append(artifact.getDecisions().size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("Задачи (").append(artifact.getTasks().size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (PlanTask t : artifact.getTasks()) {
            sb.append("  - [").append(t.getStatus()).append("] ").append(t.getId()) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(": ").append(t.getDescription()); //$NON-NLS-1$
            if (t.getAcceptance().isEmpty()) {
                sb.append("  (нет acceptance!)"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        if (!artifact.getVerification().isEmpty()) {
            sb.append("Проверки:\n"); //$NON-NLS-1$
            for (VerificationItem v : artifact.getVerification()) {
                sb.append("  - [").append(v.getResult()).append("] ").append(v.getCriterion()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return sb.toString();
    }

    private Session resolveSession() {
        try {
            return SessionManager.getInstance().getOrCreateCurrentSession();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[\\n,]")) //$NON-NLS-1$
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
