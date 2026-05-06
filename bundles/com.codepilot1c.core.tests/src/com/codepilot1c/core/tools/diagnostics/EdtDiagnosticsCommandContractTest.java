package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link EdtDiagnosticsCommandContract}.
 *
 * <p>Pins the per-command required-field contract for {@code edt_diagnostics}
 * and the {@code project}/{@code project_name} alias behaviour, so a refactor
 * cannot silently regress the BF-8908 minor fix.</p>
 */
public class EdtDiagnosticsCommandContractTest {

    // --- requiredFields -----------------------------------------------------

    @Test
    public void requiredFields_metadataSmokeNeedsProject() {
        assertEquals(List.of("project"), //$NON-NLS-1$
                EdtDiagnosticsCommandContract.requiredFields("metadata_smoke")); //$NON-NLS-1$
    }

    @Test
    public void requiredFields_traceExportNeedsProject() {
        assertEquals(List.of("project"), //$NON-NLS-1$
                EdtDiagnosticsCommandContract.requiredFields("trace_export")); //$NON-NLS-1$
    }

    @Test
    public void requiredFields_analyzeErrorNeedsToolResult() {
        assertEquals(List.of("tool_result"), //$NON-NLS-1$
                EdtDiagnosticsCommandContract.requiredFields("analyze_error")); //$NON-NLS-1$
    }

    @Test
    public void requiredFields_updateInfobaseAndLaunchAppNeedProjectName() {
        assertEquals(List.of("project_name"), //$NON-NLS-1$
                EdtDiagnosticsCommandContract.requiredFields("update_infobase")); //$NON-NLS-1$
        assertEquals(List.of("project_name"), //$NON-NLS-1$
                EdtDiagnosticsCommandContract.requiredFields("launch_app")); //$NON-NLS-1$
    }

    @Test
    public void requiredFields_unknownCommandReturnsEmpty() {
        assertTrue(EdtDiagnosticsCommandContract.requiredFields("does_not_exist").isEmpty()); //$NON-NLS-1$
        assertTrue(EdtDiagnosticsCommandContract.requiredFields(null).isEmpty());
    }

    // --- findFirstMissingRequired ------------------------------------------

    @Test
    public void findFirstMissing_returnsFieldNameWhenMissing() {
        assertEquals("project", //$NON-NLS-1$
                EdtDiagnosticsCommandContract.findFirstMissingRequired(
                        "metadata_smoke", //$NON-NLS-1$
                        Map.of("command", "metadata_smoke"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("tool_result", //$NON-NLS-1$
                EdtDiagnosticsCommandContract.findFirstMissingRequired(
                        "analyze_error", //$NON-NLS-1$
                        Map.of("command", "analyze_error"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void findFirstMissing_returnsNullWhenAllRequiredPresent() {
        assertNull(EdtDiagnosticsCommandContract.findFirstMissingRequired(
                "metadata_smoke", //$NON-NLS-1$
                Map.of("command", "metadata_smoke", "project", "AM"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(EdtDiagnosticsCommandContract.findFirstMissingRequired(
                "update_infobase", //$NON-NLS-1$
                Map.of("command", "update_infobase", "project_name", "AM"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void findFirstMissing_blankStringIsTreatedAsMissing() {
        assertEquals("project", //$NON-NLS-1$
                EdtDiagnosticsCommandContract.findFirstMissingRequired(
                        "metadata_smoke", //$NON-NLS-1$
                        Map.of("command", "metadata_smoke", "project", "  "))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void findFirstMissing_unknownCommandReturnsNull() {
        // Unknown commands are handled by the parent dispatcher; the contract
        // helper does not block them.
        assertNull(EdtDiagnosticsCommandContract.findFirstMissingRequired(
                "fictional", Map.of())); //$NON-NLS-1$
        assertNull(EdtDiagnosticsCommandContract.findFirstMissingRequired(null, Map.of()));
    }

    // --- missingRequiredFieldMessage ---------------------------------------

    @Test
    public void missingMessage_namesCommandFieldAndAlias() {
        String msg = EdtDiagnosticsCommandContract.missingRequiredFieldMessage(
                "metadata_smoke", "project"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must echo command:\n" + msg, msg.contains("metadata_smoke")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must echo missing field:\n" + msg, msg.contains("'project'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must mention project_name alias:\n" + msg, msg.contains("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must show example payload:\n" + msg, //$NON-NLS-1$
                msg.contains("\"command\":\"metadata_smoke\"") //$NON-NLS-1$
                && msg.contains("\"project\"")); //$NON-NLS-1$
    }

    @Test
    public void missingMessage_doesNotShowAliasForToolResult() {
        // tool_result has no alias; message must not invent one.
        String msg = EdtDiagnosticsCommandContract.missingRequiredFieldMessage(
                "analyze_error", "tool_result"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("tool_result has no alias:\n" + msg, msg.contains("alias")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- applyProjectFieldAliases ------------------------------------------

    @Test
    public void aliases_projectMirroredToProjectName() {
        Map<String, Object> in = Map.of("command", "update_infobase", "project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map<String, Object> out = EdtDiagnosticsCommandContract.applyProjectFieldAliases(in);
        assertEquals("AM", out.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("AM", out.get("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aliases_projectNameMirroredToProject() {
        Map<String, Object> in = Map.of("command", "metadata_smoke", "project_name", "AM"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map<String, Object> out = EdtDiagnosticsCommandContract.applyProjectFieldAliases(in);
        assertEquals("AM", out.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("AM", out.get("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aliases_existingValueWins() {
        // If both fields are supplied with different values, neither should be overwritten.
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        in.put("project_name", "Different"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> out = EdtDiagnosticsCommandContract.applyProjectFieldAliases(in);
        assertEquals("AM", out.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Different", out.get("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aliases_nullAndEmptyInputsHandledGracefully() {
        assertNull(EdtDiagnosticsCommandContract.applyProjectFieldAliases(null));
        assertTrue(EdtDiagnosticsCommandContract.applyProjectFieldAliases(Map.of()).isEmpty());
    }

    @Test
    public void aliases_doNotMutateInput() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("command", "update_infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        in.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        EdtDiagnosticsCommandContract.applyProjectFieldAliases(in);
        // Original map should not have gained project_name.
        assertFalse(in.containsKey("project_name")); //$NON-NLS-1$
    }

    // --- describeRequirements ----------------------------------------------

    @Test
    public void describeRequirements_listsAllFiveCommands() {
        String desc = EdtDiagnosticsCommandContract.describeRequirements();
        assertTrue(desc.contains("metadata_smoke -> project")); //$NON-NLS-1$
        assertTrue(desc.contains("trace_export -> project")); //$NON-NLS-1$
        assertTrue(desc.contains("analyze_error -> tool_result")); //$NON-NLS-1$
        assertTrue(desc.contains("update_infobase -> project_name")); //$NON-NLS-1$
        assertTrue(desc.contains("launch_app -> project_name")); //$NON-NLS-1$
    }
}
