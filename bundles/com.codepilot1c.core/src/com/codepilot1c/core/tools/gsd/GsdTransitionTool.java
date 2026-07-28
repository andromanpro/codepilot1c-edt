/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdContentRejectedException;
import com.codepilot1c.core.gsd.GsdPhase;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Transitions the GSD phase. Forward transitions: DISCOVERY → PLANNING → EXECUTING →
 * VERIFYING → CLOSED. Single allowed rollback: VERIFYING → EXECUTING (requires {@code reason}).
 *
 * <p>Schema requires {@code project_path}, {@code expected_revision}, {@code target_phase}.
 * {@code reason} is optional except for VERIFYING→EXECUTING rollback where it is required.</p>
 */
@ToolMeta(
    name = "gsd_transition",
    category = "gsd",
    mutating = true,
    tags = {"gsd", "lifecycle"}
)
public class GsdTransitionTool extends AbstractTool {

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
                "target_phase": {
                  "type": "string",
                  "enum": ["DISCOVERY", "PLANNING", "EXECUTING", "VERIFYING", "CLOSED"],
                  "description": "The phase to transition to."
                },
                "reason": {
                  "type": "string",
                  "description": "Required for VERIFYING->EXECUTING rollback: explain why."
                }
              },
              "required": ["project_path", "expected_revision", "target_phase"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Transitions the GSD phase forward (DISCOVERY->PLANNING->EXECUTING->VERIFYING->CLOSED). " //$NON-NLS-1$
                + "Allows one rollback: VERIFYING->EXECUTING with a required reason.";
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
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Illegal transition: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (GsdContentRejectedException e) {
                return ToolResult.failure("Content rejected: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_SECURITY)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleRevisionException e) {
                return ToolResult.failure("Stale revision: expected " + e.getExpectedRevision() //$NON-NLS-1$
                                + ", current " + e.getActualRevision(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
                return ToolResult.failure("Guard violation: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_GUARD)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
                return ToolResult.failure("GSD state is corrupt: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_CORRUPT)); //$NON-NLS-1$
            } catch (IOException e) {
                return ToolResult.failure("I/O error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_IO)); //$NON-NLS-1$
            } catch (RuntimeException e) {
                return ToolResult.failure("Internal error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            }
        });
    }

    private ToolResult executeInternal(ToolParameters params) throws IOException {
        String projectPath = params.requireString("project_path"); //$NON-NLS-1$
        long expectedRevision = params.requireLong("expected_revision"); //$NON-NLS-1$
        String phaseStr = params.requireString("target_phase"); //$NON-NLS-1$
        GsdPhase targetPhase = GsdPhase.fromName(phaseStr);
        if (targetPhase == null) {
            return ToolResult.failure("Unknown phase: " + phaseStr, //$NON-NLS-1$
                    GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
        }
        Map<String, Object> raw = params.getRaw();
        String reason = raw.containsKey("reason") //$NON-NLS-1$
                ? params.optString("reason", null) : null; //$NON-NLS-1$

        GsdState state = GsdWorkflowService.transitionPhase(
                projectPath, expectedRevision, targetPhase, reason);
        JsonObject structured = GsdWorkflowService.buildResult(
                true, "gsd_transition", state.revision(), state.phase(), null); //$NON-NLS-1$
        return ToolResult.success(
                "Phase transitioned to " + state.phase() + ". Revision: " + state.revision(), //$NON-NLS-1$ //$NON-NLS-2$
                structured);
    }
}
