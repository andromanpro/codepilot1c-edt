package com.codepilot1c.core.tools.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edt.forms.HandlerStubReport;
import com.codepilot1c.core.edt.forms.UpdateFormModelResult;

public class FormResultContractTest {

    @Test
    public void mutateFormModelOperationsAppliedExcludesStubSummaries() {
        UpdateFormModelResult result = new UpdateFormModelResult(
                "Project", //$NON-NLS-1$
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                2,
                List.of("add_field[1]", "add_command[2]"), //$NON-NLS-1$ //$NON-NLS-2$
                new HandlerStubReport(List.of("Run"), List.of("Existing"))); //$NON-NLS-1$ //$NON-NLS-2$

        String text = result.formatForLlm();

        assertEquals(2, result.operationsApplied());
        assertTrue(text.contains("Применено операций: 2")); //$NON-NLS-1$
        assertFalse(text.contains("stub generated")); //$NON-NLS-1$
        assertFalse(text.contains("stub skipped")); //$NON-NLS-1$
    }

    @Test
    public void mutateFormModelHeaderDowngradesOnSkippedStub() {
        UpdateFormModelResult partial = result(new HandlerStubReport(List.of(), List.of("Existing"))); //$NON-NLS-1$
        UpdateFormModelResult complete = result(new HandlerStubReport(List.of("Written"), List.of())); //$NON-NLS-1$

        assertTrue(partial.formatForLlm().startsWith("⚠️ Модель формы обновлена ЧАСТИЧНО")); //$NON-NLS-1$
        assertTrue(complete.formatForLlm().startsWith("✅ Модель формы обновлена.")); //$NON-NLS-1$
    }

    private static UpdateFormModelResult result(HandlerStubReport report) {
        return new UpdateFormModelResult("Project", "Catalog.C.Form.F", 0, List.of(), report); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
