/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.gsd;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent GSD planning artifact for one agent task (the plugin's analogue of
 * GSD's per-phase PLAN/CONTEXT/VERIFICATION files, condensed into one record).
 *
 * <p>Holds the goal (for goal-backward verification), the locked DISCUSS
 * decisions, the decomposed tasks, the verification results, and a checkpoint so
 * the work can resume after a context reset. Plain Java model (no Eclipse deps)
 * so it serializes cleanly via Gson and is unit-testable.</p>
 *
 * <p>Phase 1 (foundation): this is the storage shape. The orchestrator that
 * drives DISCUSS→PLAN→EXECUTE→VERIFY and writes/reads it lands in later phases.</p>
 */
public class PlanArtifact {

    /** Status of a single planned task. */
    public enum TaskStatus {
        PENDING,
        IN_PROGRESS,
        DONE,
        FAILED
    }

    /** Result of a verification criterion. */
    public enum VerifyResult {
        PENDING,
        PASS,
        FAIL
    }

    private String sessionId;
    private String projectPath;
    private String goal;
    private TaskPhase phase = TaskPhase.DISCUSS;
    private String status = "active"; //$NON-NLS-1$
    private Instant createdAt;
    private Instant updatedAt;
    private final List<Decision> decisions = new ArrayList<>();
    private final List<PlanTask> tasks = new ArrayList<>();
    private final List<VerificationItem> verification = new ArrayList<>();
    private Checkpoint checkpoint;

    public PlanArtifact() {
        // for Gson
    }

    public PlanArtifact(String sessionId, String projectPath, String goal) {
        this.sessionId = sessionId;
        this.projectPath = projectPath;
        this.goal = goal;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public TaskPhase getPhase() {
        return phase;
    }

    public void setPhase(TaskPhase phase) {
        this.phase = phase != null ? phase : TaskPhase.DISCUSS;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Decision> getDecisions() {
        return decisions;
    }

    public List<PlanTask> getTasks() {
        return tasks;
    }

    public List<VerificationItem> getVerification() {
        return verification;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(Checkpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    /** Marks the artifact updated (call after every mutation before persisting). */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Returns whether this plan represents unfinished work worth resuming after a
     * context reset (not DONE, not marked done, and has a goal or tasks).
     *
     * @return true if resumable
     */
    public boolean isResumable() {
        if (phase == TaskPhase.DONE || "done".equals(status)) { //$NON-NLS-1$
            return false;
        }
        boolean hasGoal = goal != null && !goal.isBlank();
        return hasGoal || !tasks.isEmpty();
    }

    /**
     * Builds a concise resume hint (phase, goal, task progress, checkpoint) to
     * inject into the system prompt so the agent continues the plan instead of
     * starting over.
     *
     * @return the resume hint text
     */
    public String resumeSummary() {
        long done = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Незавершённый план GSD (продолжение)\n"); //$NON-NLS-1$
        sb.append("Для этой сессии уже есть план — ПРОДОЛЖИ его, не начинай заново.\n"); //$NON-NLS-1$
        sb.append("Текущая фаза: ").append(phase).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (goal != null && !goal.isBlank()) {
            sb.append("Цель: ").append(goal).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("Задачи: ").append(done).append("/").append(tasks.size()).append(" выполнено\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (checkpoint != null) {
            if (checkpoint.getLastCompletedTaskId() != null && !checkpoint.getLastCompletedTaskId().isBlank()) {
                sb.append("Последняя завершённая задача: ").append(checkpoint.getLastCompletedTaskId()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (checkpoint.getNextAction() != null && !checkpoint.getNextAction().isBlank()) {
                sb.append("Следующее действие: ").append(checkpoint.getNextAction()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        sb.append("Вызови gsd_plan status, чтобы загрузить план, и продолжай с текущей фазы.\n"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * A locked decision captured during DISCUSS (what the user chose).
     */
    public static class Decision {
        private String question;
        private String answer;
        private boolean locked = true;

        public Decision() {
        }

        public Decision(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }
    }

    /**
     * An atomic planned task with its acceptance criteria and status.
     */
    public static class PlanTask {
        private String id;
        private String description;
        private final List<String> files = new ArrayList<>();
        private final List<String> acceptance = new ArrayList<>();
        private String toolHint;
        private int wave;
        private TaskStatus status = TaskStatus.PENDING;

        public PlanTask() {
        }

        public PlanTask(String id, String description) {
            this.id = id;
            this.description = description;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getFiles() {
            return files;
        }

        public List<String> getAcceptance() {
            return acceptance;
        }

        public String getToolHint() {
            return toolHint;
        }

        public void setToolHint(String toolHint) {
            this.toolHint = toolHint;
        }

        public int getWave() {
            return wave;
        }

        public void setWave(int wave) {
            this.wave = wave;
        }

        public TaskStatus getStatus() {
            return status;
        }

        public void setStatus(TaskStatus status) {
            this.status = status != null ? status : TaskStatus.PENDING;
        }
    }

    /**
     * A goal-backward verification criterion and its outcome.
     */
    public static class VerificationItem {
        private String criterion;
        private String method;
        private VerifyResult result = VerifyResult.PENDING;
        private String evidence;

        public VerificationItem() {
        }

        public VerificationItem(String criterion, String method) {
            this.criterion = criterion;
            this.method = method;
        }

        public String getCriterion() {
            return criterion;
        }

        public void setCriterion(String criterion) {
            this.criterion = criterion;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public VerifyResult getResult() {
            return result;
        }

        public void setResult(VerifyResult result) {
            this.result = result != null ? result : VerifyResult.PENDING;
        }

        public String getEvidence() {
            return evidence;
        }

        public void setEvidence(String evidence) {
            this.evidence = evidence;
        }
    }

    /**
     * Resume checkpoint: enough to continue after a context reset.
     */
    public static class Checkpoint {
        private String lastCompletedTaskId;
        private String nextAction;
        private Instant timestamp;

        public Checkpoint() {
        }

        public Checkpoint(String lastCompletedTaskId, String nextAction) {
            this.lastCompletedTaskId = lastCompletedTaskId;
            this.nextAction = nextAction;
            this.timestamp = Instant.now();
        }

        public String getLastCompletedTaskId() {
            return lastCompletedTaskId;
        }

        public void setLastCompletedTaskId(String lastCompletedTaskId) {
            this.lastCompletedTaskId = lastCompletedTaskId;
        }

        public String getNextAction() {
            return nextAction;
        }

        public void setNextAction(String nextAction) {
            this.nextAction = nextAction;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Instant timestamp) {
            this.timestamp = timestamp;
        }
    }
}
