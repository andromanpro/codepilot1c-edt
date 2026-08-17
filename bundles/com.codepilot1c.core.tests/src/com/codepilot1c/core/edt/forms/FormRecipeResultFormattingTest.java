package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class FormRecipeResultFormattingTest {

    @Test
    public void noStubsKeepsSuccessHeaderAndDeterministicCounters() {
        FormRecipeResult result = result(List.of(), List.of());

        String text = result.formatForLlm();

        assertTrue(text.startsWith("✅ Рецепт формы применен.")); //$NON-NLS-1$
        assertTrue(text.contains("Записано заглушек обработчиков: 0")); //$NON-NLS-1$
        assertTrue(text.contains("Пропущено заглушек (уже существуют): 0")); //$NON-NLS-1$
    }

    @Test
    public void skippedStubDowngradesHeaderAndNamesRequiredFollowUp() {
        FormRecipeResult result = result(List.of(), List.of("FormOnOpen")); //$NON-NLS-1$

        String text = result.formatForLlm();

        assertTrue(text.startsWith("⚠️ Рецепт формы применен ЧАСТИЧНО")); //$NON-NLS-1$
        assertTrue(text.contains("часть заглушек не записана")); //$NON-NLS-1$
        assertFalse(text.startsWith("✅")); //$NON-NLS-1$
        assertTrue(text.contains("FormOnOpen")); //$NON-NLS-1$
        assertTrue(text.contains("тело не изменено")); //$NON-NLS-1$
        assertTrue(text.contains("get_diagnostics (scope=file и scope=project)")); //$NON-NLS-1$
        assertTrue(text.contains("Пропущено заглушек (уже существуют): 1")); //$NON-NLS-1$
    }

    @Test
    public void writtenStubsAreReportedExplicitly() {
        FormRecipeResult result = result(List.of("FormOnOpen", "ItemOnChange"), List.of()); //$NON-NLS-1$ //$NON-NLS-2$

        String text = result.formatForLlm();

        assertTrue(text.startsWith("✅")); //$NON-NLS-1$
        assertTrue(text.contains("Записано заглушек обработчиков: 2")); //$NON-NLS-1$
        assertTrue(text.contains("- FormOnOpen")); //$NON-NLS-1$
        assertTrue(text.contains("- ItemOnChange")); //$NON-NLS-1$
    }

    private static FormRecipeResult result(List<String> written, List<String> skipped) {
        return new FormRecipeResult(
                "Project", //$NON-NLS-1$
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                1,
                0,
                0,
                1,
                List.of("add_event_handler[1]: event=OnOpen, handler=FormOnOpen"), //$NON-NLS-1$
                new HandlerStubReport(written, skipped));
    }
}
