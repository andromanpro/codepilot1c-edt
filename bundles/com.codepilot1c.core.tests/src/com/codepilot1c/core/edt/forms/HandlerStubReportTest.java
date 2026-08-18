package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class HandlerStubReportTest {

    @Test
    public void emptyReportIsCompleteAndRendersZeroCounters() {
        HandlerStubReport report = HandlerStubReport.empty();

        String text = report.formatForLlm();

        assertTrue(report.complete());
        assertTrue(text.contains("Записано заглушек обработчиков: 0")); //$NON-NLS-1$
        assertTrue(text.contains("Пропущено заглушек (уже существуют): 0")); //$NON-NLS-1$
        assertFalse(text.contains("Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void skippedStubMakesReportIncompleteAndAddsFollowUp() {
        HandlerStubReport report = new HandlerStubReport(List.of(), List.of("FormOnOpen")); //$NON-NLS-1$

        String text = report.formatForLlm();

        assertFalse(report.complete());
        assertTrue(text.contains(
                "- FormOnOpen: процедура с таким именем уже существует в модуле — тело не изменено")); //$NON-NLS-1$
        assertTrue(text.contains(
                "Проверьте тело процедуры в Module.bsl и get_diagnostics (scope=file и scope=project).")); //$NON-NLS-1$
    }

    @Test
    public void headerSelectsPartialTextOnlyWhenIncomplete() {
        assertEquals("ok", HandlerStubReport.empty().header("ok", "partial")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("partial", //$NON-NLS-1$
                new HandlerStubReport(List.of(), List.of("Handler")).header("ok", "partial")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void reportComponentsAreImmutableAndNullTolerant() {
        List<String> written = new ArrayList<>(List.of("First")); //$NON-NLS-1$
        HandlerStubReport report = new HandlerStubReport(written, null);

        written.add("Second"); //$NON-NLS-1$

        assertEquals(List.of("First"), report.written()); //$NON-NLS-1$
        assertTrue(report.skippedExisting().isEmpty());
    }

    @Test
    public void recipeAndModelResultsRenderIdenticalStubBlock() {
        HandlerStubReport report = new HandlerStubReport(List.of("Written"), List.of("Skipped")); //$NON-NLS-1$ //$NON-NLS-2$
        FormRecipeResult recipe = new FormRecipeResult(
                "Project", "Catalog.C.Form.F", 0, 0, 0, 0, List.of(), report); //$NON-NLS-1$ //$NON-NLS-2$
        UpdateFormModelResult model = new UpdateFormModelResult(
                "Project", "Catalog.C.Form.F", 0, List.of(), report); //$NON-NLS-1$ //$NON-NLS-2$

        String block = report.formatForLlm();

        assertTrue(recipe.formatForLlm().contains(block));
        assertTrue(model.formatForLlm().contains(block));
    }

}
