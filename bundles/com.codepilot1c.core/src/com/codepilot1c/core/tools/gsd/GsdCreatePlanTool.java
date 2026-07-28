/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdTask;
import com.codepilot1c.core.gsd.GsdTaskStatus;
import com.codepilot1c.core.gsd.GsdWave;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Creates a plan: sets the goal, tasks, and waves. Does <em>not</em> change the phase;
 * phase transitions are performed exclusively by {@code gsd_transition}.
 * Allowed in PLANNING phase only.
 *
 * <p>Schema requires {@code project_path}, {@code expected_revision}, {@code goal},
 * {@code tasks} (array of objects with {@code id}, {@code title}, optionally {@code status},
 * {@code wave_id}, {@code depends_on}), and {@code waves} (array with {@code id}, {@code name},
 * optionally {@code goal}, {@code task_ids}).</p>
 */
@ToolMeta(
    name = "gsd_create_plan",
    category = "gsd",
    mutating = true,
    tags = {"gsd", "planning"}
)
public class GsdCreatePlanTool extends AbstractTool {

    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_path": {
                  "type": "string",
                  "description": "Absolute path to the project root."
                },
                "expected_revision": {
                  "type": "integer",
                  "description": "Expected revision for optimistic concurrency."
                },
                "goal": {
                  "type": "string",
                  "description": "The project goal statement."
                },
                "tasks": {
                  "type": "array",
                  "description": "Work items to plan.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "title": {"type": "string"},
                      "status": {"type": "string", "enum": ["PENDING", "IN_PROGRESS", "BLOCKED", "DONE"]},
                      "wave_id": {"type": "string"},
                      "depends_on": {"type": "array", "items": {"type": "string"}}
                    },
                    "required": ["id", "title"],
                    "additionalProperties": false
                  }
                },
                "waves": {
                  "type": "array",
                  "description": "Ordered waves of tasks.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "name": {"type": "string"},
                      "goal": {"type": "string"},
                      "task_ids": {"type": "array", "items": {"type": "string"}}
                    },
                    "required": ["id", "name"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["project_path", "expected_revision", "goal", "tasks", "waves"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Creates a GSD plan by setting the goal, tasks, and waves. " //$NON-NLS-1$
                + "Does not change the phase; use gsd_transition for that.";
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeInternal(params);
            } catch (ToolParameterException e) {
                return ToolResult.failure("Parameter error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Invalid input: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalStateException e) {
                return ToolResult.failure("Illegal state: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleRevisionException e) {
                return ToolResult.failure("Stale revision: expected " + e.getExpectedRevision() //$NON-NLS-1$
                                + ", current " + e.getActualRevision(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
                return ToolResult.failure("Guard violation: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_GUARD)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
                return ToolResult.failure("GSD state is corrupt: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_CORRUPT)); //$NON-NLS-1$
            } catch (IOException e) {
                return ToolResult.failure("I/O error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_IO)); //$NON-NLS-1$
            } catch (RuntimeException e) {
                return ToolResult.failure("Internal error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_create_plan", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            }
        });
    }

    private ToolResult executeInternal(ToolParameters params) throws IOException {
        String projectPath = params.requireString("project_path"); //$NON-NLS-1$
        long expectedRevision = params.requireLong("expected_revision"); //$NON-NLS-1$
        String goal = params.requireString("goal"); //$NON-NLS-1$

        Object rawTasksObj = params.getRaw().get("tasks"); //$NON-NLS-1$
        Object rawWavesObj = params.getRaw().get("waves"); //$NON-NLS-1$

        if (!(rawTasksObj instanceof List<?>)) {
            throw new IllegalArgumentException("'tasks' must be an array"); //$NON-NLS-1$
        }
        if (!(rawWavesObj instanceof List<?>)) {
            throw new IllegalArgumentException("'waves' must be an array"); //$NON-NLS-1$
        }

        List<?> rawTasks = (List<?>) rawTasksObj;
        List<?> rawWaves = (List<?>) rawWavesObj;

        if (rawTasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty"); //$NON-NLS-1$
        }
        if (rawWaves.isEmpty()) {
            throw new IllegalArgumentException("waves must not be empty"); //$NON-NLS-1$
        }

        List<GsdTask> tasks = parseTasks(rawTasks);
        List<GsdWave> waves = parseWaves(rawWaves);

        GsdState state = GsdWorkflowService.createPlan(
                projectPath, expectedRevision, goal, tasks, waves);
        JsonObject structured = GsdWorkflowService.buildResult(
                true, "gsd_create_plan", state.revision(), state.phase(), null); //$NON-NLS-1$
        return ToolResult.success(
                "Plan created. Phase: " + state.phase() + ", Revision: " + state.revision(), //$NON-NLS-1$ //$NON-NLS-2$
                structured);
    }

    @SuppressWarnings("unchecked")
    private List<GsdTask> parseTasks(List<?> rawTasks) {
        List<GsdTask> tasks = new ArrayList<>();
        for (int i = 0; i < rawTasks.size(); i++) {
            Object raw = rawTasks.get(i);
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("tasks[" + i + "] is not an object"); //$NON-NLS-1$
            }
            Map<String, Object> m = (Map<String, Object>) raw;

            Object idObj = m.get("id"); //$NON-NLS-1$
            if (!(idObj instanceof String) || ((String) idObj).isBlank()) {
                throw new IllegalArgumentException("tasks[" + i + "].id must be a non-blank string"); //$NON-NLS-1$
            }
            String id = (String) idObj;

            Object titleObj = m.get("title"); //$NON-NLS-1$
            if (!(titleObj instanceof String) || ((String) titleObj).isBlank()) {
                throw new IllegalArgumentException("tasks[" + i + "].title must be a non-blank string"); //$NON-NLS-1$
            }
            String title = (String) titleObj;

            String statusStr = (String) m.get("status"); //$NON-NLS-1$
            GsdTaskStatus status = GsdTaskStatus.PENDING;
            if (statusStr != null) {
                status = GsdTaskStatus.fromName(statusStr);
                if (status == null) {
                    throw new IllegalArgumentException("tasks[" + i + "].status: unknown '" + statusStr //$NON-NLS-1$
                            + "'; must be PENDING, IN_PROGRESS, BLOCKED, or DONE"); //$NON-NLS-1$
                }
            }

            String waveId = safeString(m.get("wave_id")); //$NON-NLS-1$

            List<String> dependsOn = safeStringList(m.get("depends_on"), i, "task.depends_on"); //$NON-NLS-1$ //$NON-NLS-2$

            tasks.add(new GsdTask(id, title, status, waveId, dependsOn, List.of()));
        }
        return tasks;
    }

    @SuppressWarnings("unchecked")
    private List<GsdWave> parseWaves(List<?> rawWaves) {
        List<GsdWave> waves = new ArrayList<>();
        for (int i = 0; i < rawWaves.size(); i++) {
            Object raw = rawWaves.get(i);
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("waves[" + i + "] is not an object"); //$NON-NLS-1$
            }
            Map<String, Object> m = (Map<String, Object>) raw;

            Object idObj = m.get("id"); //$NON-NLS-1$
            if (!(idObj instanceof String) || ((String) idObj).isBlank()) {
                throw new IllegalArgumentException("waves[" + i + "].id must be a non-blank string"); //$NON-NLS-1$
            }
            String id = (String) idObj;

            Object nameObj = m.get("name"); //$NON-NLS-1$
            if (!(nameObj instanceof String) || ((String) nameObj).isBlank()) {
                throw new IllegalArgumentException("waves[" + i + "].name must be a non-blank string"); //$NON-NLS-1$
            }
            String name = (String) nameObj;

            String goal = safeString(m.get("goal")); //$NON-NLS-1$

            List<String> taskIds = safeStringList(m.get("task_ids"), i, "wave.task_ids"); //$NON-NLS-1$ //$NON-NLS-2$

            waves.add(new GsdWave(id, name, goal != null ? goal : "", //$NON-NLS-1$
                    taskIds != null ? taskIds : List.of()));
        }
        return waves;
    }

    /** Returns null if obj is not a string. */
    private String safeString(Object obj) {
        if (obj instanceof String) {
            return (String) obj;
        }
        return null;
    }

    /**
     * Validates that obj is a list of strings.
     * @return empty list for null, validated list otherwise
     * @throws IllegalArgumentException if not a list or contains non-strings
     */
    @SuppressWarnings("unchecked")
    private List<String> safeStringList(Object obj, int index, String field) {
        if (obj == null) {
            return List.of();
        }
        if (!(obj instanceof List<?>)) {
            throw new IllegalArgumentException(field + "[" + index + "] is not an array"); //$NON-NLS-1$
        }
        List<?> list = (List<?>) obj;
        List<String> result = new ArrayList<>(list.size());
        for (int j = 0; j < list.size(); j++) {
            Object item = list.get(j);
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(field + "[" + index + "][" + j //$NON-NLS-1$
                        + "] must be a string, got " + item.getClass().getSimpleName()); //$NON-NLS-1$
            }
            result.add((String) item);
        }
        return result;
    }
}
