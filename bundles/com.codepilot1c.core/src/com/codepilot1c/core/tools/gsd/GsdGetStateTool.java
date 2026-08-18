/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdDecision;
import com.codepilot1c.core.gsd.GsdEvidence;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdTask;
import com.codepilot1c.core.gsd.GsdWave;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Reads the current GSD state for a project.
 *
 * <p>Schema requires {@code project_path} only. Returns a bounded text summary
 * and a full structured JSON payload containing the complete state (phase, revision,
 * goal, decisions, tasks, waves, evidence).</p>
 */
@ToolMeta(
    name = "gsd_get_state",
    category = "gsd",
    mutating = false,
    tags = {"gsd", "read-only"}
)
public class GsdGetStateTool extends AbstractTool {

    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_path": {
                  "type": "string",
                  "description": "Absolute path to the project root."
                }
              },
              "required": ["project_path"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Reads the current GSD state for a project, including phase, revision, goal, " //$NON-NLS-1$
                + "decisions, tasks, waves, and evidence.";
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String projectPath = params.requireString("project_path"); //$NON-NLS-1$
                GsdState state = GsdWorkflowService.getState(projectPath);
                return ToolResult.success(formatSummary(state), buildStructured(state));
            } catch (ToolParameterException e) {
                return ToolResult.failure("Parameter error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_get_state", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
                return ToolResult.failure("GSD state is corrupt: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_get_state", 0, null, GsdWorkflowService.ERR_CORRUPT)); //$NON-NLS-1$
            } catch (IOException e) {
                return ToolResult.failure("I/O error reading GSD state: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_get_state", 0, null, GsdWorkflowService.ERR_IO)); //$NON-NLS-1$
            }
        });
    }

    static String formatSummary(GsdState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("GSD State:\n"); //$NON-NLS-1$
        sb.append("  Phase: ").append(state.phase()).append('\n'); //$NON-NLS-1$
        sb.append("  Revision: ").append(state.revision()).append('\n'); //$NON-NLS-1$
        sb.append("  Goal: ").append(boundedPreview(state.goal(), 240)).append('\n'); //$NON-NLS-1$
        sb.append("  Decisions: ").append(state.decisions().size()).append('\n'); //$NON-NLS-1$
        sb.append("  Tasks: ").append(state.tasks().size()).append('\n'); //$NON-NLS-1$
        sb.append("  Waves: ").append(state.waves().size()).append('\n'); //$NON-NLS-1$
        sb.append("  Evidence: ").append(state.evidence().size()).append('\n'); //$NON-NLS-1$
        return sb.toString().stripTrailing();
    }

    /**
     * Returns a bounded preview of {@code text} whose total length
     * (including the ellipsis \u2026) never exceeds {@code maxChars}.
     * If {@code maxChars} is less than 1, returns "". Truncation
     * avoids breaking surrogate pairs.
     */
    static String boundedPreview(String text, int maxChars) {
        if (maxChars <= 0) {
            return ""; //$NON-NLS-1$
        }
        if (text == null || text.isEmpty()) {
            return "(none)"; //$NON-NLS-1$
        }
        // Fits within the budget — return as-is, no ellipsis needed.
        if (text.length() <= maxChars) {
            return text;
        }
        // Must truncate: reserve 1 char for ellipsis.
        int end = maxChars - 1;
        // If the cut leaves a high surrogate orphaned, back up by one.
        if (end > 0 && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        // If we backed up all the way to 0, return just the ellipsis.
        if (end <= 0) {
            return "\u2026"; //$NON-NLS-1$
        }
        return text.substring(0, end) + "\u2026"; //$NON-NLS-1$
    }

    /**
     * Builds a full structured payload with complete GSD state.
     * Includes status/operation/revision/phase plus all decisions, tasks, waves, evidence.
     */
    static JsonObject buildStructured(GsdState state) {
        JsonObject root = new JsonObject();
        root.addProperty("status", "success"); //$NON-NLS-1$ //$NON-NLS-2$
        root.addProperty("operation", "gsd_get_state"); //$NON-NLS-1$ //$NON-NLS-2$
        root.addProperty("revision", state.revision()); //$NON-NLS-1$
        root.addProperty("phase", state.phase().name()); //$NON-NLS-1$
        root.addProperty("goal", state.goal()); //$NON-NLS-1$

        // Decisions
        com.google.gson.JsonArray decArr = new com.google.gson.JsonArray();
        for (GsdDecision d : state.decisions()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id()); //$NON-NLS-1$
            o.addProperty("summary", d.summary()); //$NON-NLS-1$
            o.addProperty("rationale", d.rationale()); //$NON-NLS-1$
            com.google.gson.JsonArray alt = new com.google.gson.JsonArray();
            for (String a : d.alternatives()) {
                alt.add(a);
            }
            o.add("alternatives", alt); //$NON-NLS-1$
            decArr.add(o);
        }
        root.add("decisions", decArr); //$NON-NLS-1$

        // Tasks
        com.google.gson.JsonArray taskArr = new com.google.gson.JsonArray();
        for (GsdTask t : state.tasks()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id()); //$NON-NLS-1$
            o.addProperty("title", t.title()); //$NON-NLS-1$
            o.addProperty("status", t.status().name()); //$NON-NLS-1$
            if (t.waveId() != null) {
                o.addProperty("wave_id", t.waveId()); //$NON-NLS-1$
            }
            com.google.gson.JsonArray depArr = new com.google.gson.JsonArray();
            for (String dep : t.dependsOn()) {
                depArr.add(dep);
            }
            o.add("depends_on", depArr); //$NON-NLS-1$
            com.google.gson.JsonArray evArr = new com.google.gson.JsonArray();
            for (String ev : t.evidenceIds()) {
                evArr.add(ev);
            }
            o.add("evidence_ids", evArr); //$NON-NLS-1$
            o.addProperty("execution_kind", t.executionKind().name()); //$NON-NLS-1$
            taskArr.add(o);
        }
        root.add("tasks", taskArr); //$NON-NLS-1$

        // Waves
        com.google.gson.JsonArray waveArr = new com.google.gson.JsonArray();
        for (GsdWave w : state.waves()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", w.id()); //$NON-NLS-1$
            o.addProperty("name", w.name()); //$NON-NLS-1$
            o.addProperty("goal", w.goal()); //$NON-NLS-1$
            com.google.gson.JsonArray tidArr = new com.google.gson.JsonArray();
            for (String tid : w.taskIds()) {
                tidArr.add(tid);
            }
            o.add("task_ids", tidArr); //$NON-NLS-1$
            waveArr.add(o);
        }
        root.add("waves", waveArr); //$NON-NLS-1$

        // Evidence
        com.google.gson.JsonArray evArr = new com.google.gson.JsonArray();
        for (GsdEvidence e : state.evidence()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id()); //$NON-NLS-1$
            o.addProperty("description", e.description()); //$NON-NLS-1$
            o.addProperty("provenance", e.provenance().name()); //$NON-NLS-1$
            o.addProperty("created_at", e.createdAt().toString()); //$NON-NLS-1$
            com.google.gson.JsonArray tidArr = new com.google.gson.JsonArray();
            for (String tid : e.taskIds()) {
                tidArr.add(tid);
            }
            o.add("task_ids", tidArr); //$NON-NLS-1$
            o.addProperty("captured_phase", e.capturedPhase().name()); //$NON-NLS-1$
            evArr.add(o);
        }
        root.add("evidence", evArr); //$NON-NLS-1$

        return root;
    }
}
