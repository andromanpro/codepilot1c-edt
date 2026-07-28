/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Tests for GSD tool schemas, registration, names, metadata, and execution.
 */
public class GsdToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String projectPath;

    @Before
    public void setUp() throws IOException {
        projectPath = tmp.newFolder("project").getAbsolutePath(); //$NON-NLS-1$
    }

    // ---- Registry registration -------------------------------------------

    @Test
    public void allGsdToolsRegisteredInDefaultTools() {
        ToolRegistry registry = ToolRegistry.getInstance();
        assertNotNull(registry.getTool("gsd_get_state")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_record_decision")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_create_plan")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_update_task")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_record_evidence")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_transition")); //$NON-NLS-1$
    }

    @Test
    public void gsdGetStateHasCorrectMeta() {
        GsdGetStateTool tool = new GsdGetStateTool();
        assertEquals("gsd_get_state", tool.getName()); //$NON-NLS-1$
        assertEquals("gsd", tool.getCategory()); //$NON-NLS-1$
        assertFalse(tool.isMutating());
        assertTrue(tool.getTags().contains("gsd")); //$NON-NLS-1$
        assertTrue(tool.getTags().contains("read-only")); //$NON-NLS-1$
        ToolMeta meta = tool.getClass().getAnnotation(ToolMeta.class);
        assertNotNull(meta);
        assertEquals("gsd_get_state", meta.name()); //$NON-NLS-1$
        assertEquals("gsd", meta.category()); //$NON-NLS-1$
        assertFalse(meta.mutating());
    }

    @Test
    public void gsdRecordDecisionHasCorrectMeta() {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        assertEquals("gsd_record_decision", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
        ToolMeta meta = tool.getClass().getAnnotation(ToolMeta.class);
        assertNotNull(meta);
        assertTrue(meta.mutating());
    }

    @Test
    public void gsdCreatePlanHasCorrectMeta() {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        assertEquals("gsd_create_plan", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    @Test
    public void gsdUpdateTaskHasCorrectMeta() {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        assertEquals("gsd_update_task", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    @Test
    public void gsdRecordEvidenceHasCorrectMeta() {
        GsdRecordEvidenceTool tool = new GsdRecordEvidenceTool();
        assertEquals("gsd_record_evidence", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    @Test
    public void gsdTransitionHasCorrectMeta() {
        GsdTransitionTool tool = new GsdTransitionTool();
        assertEquals("gsd_transition", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    // ---- Schema alignment ------------------------------------------------

    @Test
    public void allSchemasRequireProjectPath() {
        String[] schemas = {
            new GsdGetStateTool().getParameterSchema(),
            new GsdRecordDecisionTool().getParameterSchema(),
            new GsdCreatePlanTool().getParameterSchema(),
            new GsdUpdateTaskTool().getParameterSchema(),
            new GsdRecordEvidenceTool().getParameterSchema(),
            new GsdTransitionTool().getParameterSchema(),
        };
        for (String schema : schemas) {
            assertTrue("schema must contain project_path: " + schema, schema.contains("\"project_path\"")); //$NON-NLS-1$
            assertTrue("schema must have additionalProperties=false: " + schema, schema.contains("\"additionalProperties\": false")); //$NON-NLS-1$
        }
    }

    @Test
    public void mutationSchemasRequireExpectedRevision() {
        String[] schemas = {
            new GsdRecordDecisionTool().getParameterSchema(),
            new GsdCreatePlanTool().getParameterSchema(),
            new GsdUpdateTaskTool().getParameterSchema(),
            new GsdRecordEvidenceTool().getParameterSchema(),
            new GsdTransitionTool().getParameterSchema(),
        };
        for (String schema : schemas) {
            assertTrue("mutation schema must require expected_revision: " + schema, schema.contains("\"expected_revision\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void readOnlySchemaDoesNotRequireExpectedRevision() {
        String schema = new GsdGetStateTool().getParameterSchema();
        assertFalse("gsd_get_state should not require expected_revision", schema.contains("\"expected_revision\"")); //$NON-NLS-1$
    }

    @Test
    public void gsdUpdateTaskSchemaOnlyExposesStatus() {
        String schema = new GsdUpdateTaskTool().getParameterSchema();
        assertTrue(schema.contains("\"status\"")); //$NON-NLS-1$
        assertFalse("schema must not expose title", schema.contains("\"title\"")); //$NON-NLS-1$
        assertFalse("schema must not expose wave_id", schema.contains("\"wave_id\"")); //$NON-NLS-1$
        assertFalse("schema must not expose depends_on", schema.contains("\"depends_on\"")); //$NON-NLS-1$
        assertFalse("schema must not expose evidence_ids", schema.contains("\"evidence_ids\"")); //$NON-NLS-1$
    }

    @Test
    public void gsdUpdateTaskRequiresStatus() {
        String schema = new GsdUpdateTaskTool().getParameterSchema();
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"status\"")); //$NON-NLS-1$
    }

    @Test
    public void allToolsHaveNonBlankDescription() {
        assertFalse(new GsdGetStateTool().getDescription().isBlank());
        assertFalse(new GsdRecordDecisionTool().getDescription().isBlank());
        assertFalse(new GsdCreatePlanTool().getDescription().isBlank());
        assertFalse(new GsdUpdateTaskTool().getDescription().isBlank());
        assertFalse(new GsdRecordEvidenceTool().getDescription().isBlank());
        assertFalse(new GsdTransitionTool().getDescription().isBlank());
    }

    // ---- gsd_get_state execution -----------------------------------------

    @Test
    public void getStateReturnsFreshState() throws ExecutionException, InterruptedException {
        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = tool.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("DISCOVERY")); //$NON-NLS-1$
        assertTrue(result.getContent().contains("Revision: 0")); //$NON-NLS-1$
        // Structured data must be present with full state
        assertTrue(result.hasStructuredData());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_get_state", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result.getStructuredData().get("tasks")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("decisions")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("waves")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("evidence")); //$NON-NLS-1$
        assertEquals(0, result.getStructuredData().getAsJsonArray("tasks").size()); //$NON-NLS-1$
    }

    @Test
    public void getStateWithInvalidPathReturnsIoError() throws ExecutionException, InterruptedException {
        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = tool.execute(Map.of("project_path", "/nonexistent/path/xyz")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("I/O error")); //$NON-NLS-1$
    }

    @Test
    public void getStateMissingProjectPathFails() throws ExecutionException, InterruptedException {
        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = tool.execute(Map.of()).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("project_path")); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewLimitsLongGoal() {
        String longGoal = "a".repeat(300);
        String preview = GsdGetStateTool.boundedPreview(longGoal, 240);
        assertTrue("preview must not exceed maxChars", preview.length() <= 240);
        assertTrue(preview.endsWith("\u2026")); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewShortGoalUnchanged() {
        assertEquals("short", GsdGetStateTool.boundedPreview("short", 240)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewExactLengthNoEllipsis() {
        // text.length() == maxChars => returned as-is, no ellipsis.
        String text = "hello";
        assertEquals(text, GsdGetStateTool.boundedPreview(text, 5)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewOneOverTruncates() {
        // text.length() == maxChars + 1 => truncated to maxChars.
        String text = "hellox";
        String preview = GsdGetStateTool.boundedPreview(text, 5);
        assertEquals("hell\u2026", preview); //$NON-NLS-1$
        assertEquals(5, preview.length());
    }

    @Test
    public void boundedPreviewNullBecomesNone() {
        assertEquals("(none)", GsdGetStateTool.boundedPreview(null, 240)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewEmptyBecomesNone() {
        assertEquals("(none)", GsdGetStateTool.boundedPreview("", 240)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewZeroMaxReturnsEmpty() {
        assertEquals("", GsdGetStateTool.boundedPreview("hello", 0)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewNegativeMaxReturnsEmpty() {
        assertEquals("", GsdGetStateTool.boundedPreview("hello", -1)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewMaxOneTruncatesToEllipsis() {
        // With maxChars=1, only room for the ellipsis itself.
        String result = GsdGetStateTool.boundedPreview("hello", 1);
        assertEquals("\u2026", result); //$NON-NLS-1$
        assertEquals(1, result.length());
    }

    @Test
    public void boundedPreviewDoesNotBreakSurrogatePair() {
        // U+1F600 (grinning face) is a surrogate pair: \uD83D\uDE00
        String emoji = "\uD83D\uDE00"; //$NON-NLS-1$
        String text = "abc" + emoji + "def";
        // text.length() == 8 chars. max=4 means substring budget 3 + ellipsis.
        String preview = GsdGetStateTool.boundedPreview(text, 4);
        assertEquals("abc\u2026", preview); //$NON-NLS-1$
        assertEquals(4, preview.length());
        // Verify no unpaired surrogates.
        assertNoUnpairedSurrogates(preview);
    }

    @Test
    public void boundedPreviewMaxTwoWithEmojiAtCut() {
        // "a\uD83D\uDE00bc", max=3 => budget 2 + ellipsis. Cut at index 2
        // leaves high surrogate orphaned, so back up to 1.
        String text = "a\uD83D\uDE00bc"; //$NON-NLS-1$
        String preview = GsdGetStateTool.boundedPreview(text, 3);
        assertEquals("a\u2026", preview); //$NON-NLS-1$
        assertEquals(2, preview.length());
        assertNoUnpairedSurrogates(preview);
    }

    @Test
    public void boundedPreviewAsciiNeverExceedsMax() {
        String text = "x".repeat(100);
        for (int max = 1; max <= 50; max++) {
            String preview = GsdGetStateTool.boundedPreview(text, max);
            assertTrue("max=" + max + " produced length=" + preview.length(), //$NON-NLS-1$
                    preview.length() <= max);
        }
    }

    private void assertNoUnpairedSurrogates(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue("unpaired high surrogate at index " + i, //$NON-NLS-1$
                        i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
            } else if (Character.isLowSurrogate(c)) {
                assertTrue("unpaired low surrogate at index " + i, //$NON-NLS-1$
                        i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
            }
        }
    }

    @Test
    public void getStateReturnsFullStructuredPayloadAfterPopulate() throws ExecutionException, InterruptedException {
        // Transition DISCOVERY -> PLANNING, then create a plan.
        GsdTransitionTool tt = new GsdTransitionTool();
        tt.execute(Map.of("project_path", projectPath, "expected_revision", 0, "target_phase", "PLANNING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        planTool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "wave 1", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = tool.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertTrue(result.hasStructuredData());
        assertEquals("Ship it", result.getStructuredString("goal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, result.getStructuredData().getAsJsonArray("tasks").size()); //$NON-NLS-1$
        assertEquals(1, result.getStructuredData().getAsJsonArray("waves").size()); //$NON-NLS-1$
        // execution_kind and captured_phase must appear in structured output.
        assertEquals("READ_ONLY", result.getStructuredData().getAsJsonArray("tasks") //$NON-NLS-1$
                .get(0).getAsJsonObject().get("execution_kind").getAsString()); //$NON-NLS-1$
    }

    // ---- gsd_record_decision execution -----------------------------------

    @Test
    public void recordDecisionSuccess() throws ExecutionException, InterruptedException {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d1", //$NON-NLS-1$
                "summary", "use JSON", //$NON-NLS-1$
                "rationale", "source of truth")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertTrue(result.hasStructuredData());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_record_decision", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, result.getStructuredInt("revision", 0)); //$NON-NLS-1$
    }

    @Test
    public void recordDecisionStaleRevision() throws ExecutionException, InterruptedException {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d1", //$NON-NLS-1$
                "summary", "use JSON", //$NON-NLS-1$
                "rationale", "why")).get(); //$NON-NLS-1$
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d2", //$NON-NLS-1$
                "summary", "use XML", //$NON-NLS-1$
                "rationale", "alt")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("stale", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recordDecisionMissingParamFails() throws ExecutionException, InterruptedException {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d1")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- gsd_create_plan execution ---------------------------------------
    // create_plan requires PLANNING phase; never changes the phase itself.

    /**
     * Helper: transitions DISCOVERY (rev 0) → PLANNING (rev 1).
     * Returns the new revision after transition (always 1).
     */
    private long transitionToPlanning() throws ExecutionException, InterruptedException {
        GsdTransitionTool tt = new GsdTransitionTool();
        tt.execute(Map.of("project_path", projectPath, "expected_revision", 0, "target_phase", "PLANNING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return 1;
    }

    @Test
    public void createPlanSuccess() throws ExecutionException, InterruptedException {
        long rev = transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "implement", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "wave 1", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result.isSuccess());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        // Phase remains PLANNING — create_plan never advances the phase.
        assertEquals("PLANNING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanEmptyTasksFails() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(),
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
    }

    @Test
    public void createPlanMalformedTaskFails() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        // tasks[0] missing id
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("title", "no-id")), //$NON-NLS-1$ //$NON-NLS-2$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanNonArrayTasksFails() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", "not an array", //$NON-NLS-1$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanMissingExecutionKindFails() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanUnknownExecutionKindFails() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "BOGUS")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanMalformedWaveFails() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("name", "no-id")))).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanTaskDependsOnNonStringElementFails() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "depends_on", List.of(42))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanDoesNotChangePhase() throws ExecutionException, InterruptedException {
        long rev = transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result.isSuccess());
        // Phase must remain PLANNING (not auto-advance to EXECUTING).
        assertEquals("PLANNING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanNestedExtraTaskKeyReturnsInvalid() throws ExecutionException, InterruptedException {
        long rev = transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "bogus", "extra")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanNestedExtraWaveKeyReturnsInvalid() throws ExecutionException, InterruptedException {
        long rev = transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "waves", List.of(Map.of("id", "w1", "name", "w", "sneaky", true)))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanFromInvalidPhaseFails() throws ExecutionException, InterruptedException {
        // In DISCOVERY (rev 0), createPlan must be rejected.
        GsdCreatePlanTool cpt = new GsdCreatePlanTool();
        ToolResult result = cpt.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanInExecutingPhaseFails() throws ExecutionException, InterruptedException {
        // DISCOVERY -> PLANNING -> create plan -> EXECUTING -> try createPlan (must fail).
        transitionToPlanning();
        GsdCreatePlanTool cpt = new GsdCreatePlanTool();
        cpt.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Transition PLANNING -> EXECUTING (rev 2).
        GsdTransitionTool tt = new GsdTransitionTool();
        tt.execute(Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // createPlan in EXECUTING must fail.
        ToolResult result = cpt.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 3, //$NON-NLS-1$
                "goal", "g2", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
    }

    // ---- gsd_update_task execution ---------------------------------------

    @Test
    public void updateTaskSuccess() throws ExecutionException, InterruptedException {
        // DISCOVERY -> PLANNING -> create plan -> EXECUTING
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        planTool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tt = new GsdTransitionTool();
        tt.execute(Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 3, //$NON-NLS-1$
                "task_id", "t1", //$NON-NLS-1$
                "status", "IN_PROGRESS")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("gsd_update_task", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateTaskNotFoundFails() throws ExecutionException, InterruptedException {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "task_id", "nonexistent", //$NON-NLS-1$
                "status", "IN_PROGRESS")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateTaskUnknownStatusFails() throws ExecutionException, InterruptedException {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "task_id", "t1", //$NON-NLS-1$
                "status", "BOGUS")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateTaskMissingStatusFails() throws ExecutionException, InterruptedException {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "task_id", "t1")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateTaskOnlyAcceptsStatusNoOtherFields() throws ExecutionException, InterruptedException {
        String schema = new GsdUpdateTaskTool().getParameterSchema();
        assertFalse("must not expose title", schema.contains("\"title\"")); //$NON-NLS-1$
        assertFalse("must not expose wave_id", schema.contains("\"wave_id\"")); //$NON-NLS-1$
        assertFalse("must not expose depends_on", schema.contains("\"depends_on\"")); //$NON-NLS-1$
        assertFalse("must not expose evidence_ids", schema.contains("\"evidence_ids\"")); //$NON-NLS-1$
    }

    // ---- gsd_record_evidence execution -----------------------------------
    // record_evidence requires EXECUTING or VERIFYING phase.

    /**
     * Helper: sets up a project in EXECUTING phase with one task t1.
     * Returns the revision after transitioning to EXECUTING.
     */
    private long setUpExecutingPhase() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        planTool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tt = new GsdTransitionTool();
        tt.execute(Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return 3;
    }

    @Test
    public void recordEvidenceSuccess() throws ExecutionException, InterruptedException {
        long rev = setUpExecutingPhase();
        GsdRecordEvidenceTool tool = new GsdRecordEvidenceTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", //$NON-NLS-1$
                "description", "test passed", //$NON-NLS-1$
                "provenance", "TESTED", //$NON-NLS-1$
                "task_ids", List.of())).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("gsd_record_evidence", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recordEvidenceInvalidProvenanceFails() throws ExecutionException, InterruptedException {
        long rev = setUpExecutingPhase();
        GsdRecordEvidenceTool tool = new GsdRecordEvidenceTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", //$NON-NLS-1$
                "description", "test passed", //$NON-NLS-1$
                "provenance", "BOGUS")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- gsd_transition execution ----------------------------------------

    @Test
    public void transitionSuccess() throws ExecutionException, InterruptedException {
        GsdTransitionTool tool = new GsdTransitionTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "target_phase", "PLANNING")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("PLANNING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void transitionIllegalFails() throws ExecutionException, InterruptedException {
        GsdTransitionTool tool = new GsdTransitionTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "target_phase", "EXECUTING")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void transitionUnknownPhaseFails() throws ExecutionException, InterruptedException {
        GsdTransitionTool tool = new GsdTransitionTool();
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "target_phase", "INVALID_PHASE")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
    }

    @Test
    public void transitionRollbackWithReasonSucceeds() throws ExecutionException, InterruptedException {
        // Set up: PLANNING -> create plan -> EXECUTING -> VERIFYING -> rollback
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        planTool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tool = new GsdTransitionTool();
        tool.execute(Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ToolResult verifying = tool.execute(Map.of("project_path", projectPath, "expected_revision", 3, "target_phase", "VERIFYING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // VERIFYING entry guard requires all tasks DONE; mark t1 DONE with evidence first.
        GsdRecordEvidenceTool evTool = new GsdRecordEvidenceTool();
        // Back up — VERIFYING can't be entered without all DONE. Reload and do it properly.
        // Actually the VERIFYING transition above failed because t1 is not DONE.
        // Let's go back: we need to reload the current revision.
        GsdGetStateTool gs = new GsdGetStateTool();
        ToolResult gsResult = gs.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        long rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        // Record evidence, then mark t1 DONE.
        evTool.execute(Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", "description", "ok", "provenance", "TESTED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "task_ids", List.of("t1"))).get(); //$NON-NLS-1$ //$NON-NLS-2$
        gsResult = gs.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        GsdUpdateTaskTool ut = new GsdUpdateTaskTool();
        ut.execute(Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "task_id", "t1", "status", "DONE")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        gsResult = gs.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        // Now VERIFYING should succeed.
        ToolResult vr = tool.execute(Map.of("project_path", projectPath, "expected_revision", rev, "target_phase", "VERIFYING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("VERIFYING transition must succeed", vr.isSuccess()); //$NON-NLS-1$
        long verifyingRev = vr.getStructuredInt("revision", 0); //$NON-NLS-1$

        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRev, //$NON-NLS-1$
                "target_phase", "EXECUTING", //$NON-NLS-1$
                "reason", "tests failed")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("EXECUTING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void transitionRollbackWithoutReasonFails() throws ExecutionException, InterruptedException {
        // Set up same as above to reach VERIFYING.
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        planTool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tool = new GsdTransitionTool();
        tool.execute(Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Record evidence + mark DONE so VERIFYING entry guard passes.
        GsdGetStateTool gs = new GsdGetStateTool();
        ToolResult gsResult = gs.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        long rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        GsdRecordEvidenceTool evTool = new GsdRecordEvidenceTool();
        evTool.execute(Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", "description", "ok", "provenance", "TESTED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "task_ids", List.of("t1"))).get(); //$NON-NLS-1$ //$NON-NLS-2$
        gsResult = gs.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        GsdUpdateTaskTool ut = new GsdUpdateTaskTool();
        ut.execute(Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "task_id", "t1", "status", "DONE")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        gsResult = gs.execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        ToolResult vr = tool.execute(Map.of("project_path", projectPath, "expected_revision", rev, "target_phase", "VERIFYING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("VERIFYING must succeed", vr.isSuccess()); //$NON-NLS-1$
        long verifyingRev = vr.getStructuredInt("revision", 0); //$NON-NLS-1$

        // Rollback without reason must fail with "invalid" (not "stale").
        ToolResult result = tool.execute(Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRev, //$NON-NLS-1$
                "target_phase", "EXECUTING")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
