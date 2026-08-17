package com.codepilot1c.core.tools.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonObject;

import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.BmState;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.FailurePhase;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.RollbackStatus;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.SerializedModelState;
import com.codepilot1c.core.edt.forms.FormRecipeResult;
import com.codepilot1c.core.edt.forms.HandlerStubReport;
import com.codepilot1c.core.edt.forms.UpdateFormModelResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;

public class FormResultContractTest {

    @Test
    public void applyFormRecipeAndMutateFormModelShareStubReportShape() {
        HandlerStubReport report = new HandlerStubReport(List.of("Written"), List.of("Skipped")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject apply = FormResultPayloads.formRecipeSuccess(recipe(report));
        JsonObject mutate = FormResultPayloads.formModelSuccess(result(report));

        List<String> shared = keys(apply).stream()
                .filter(key -> key.startsWith("handler_") || "complete".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$
                .filter(mutate::has)
                .toList();

        assertEquals(List.of(
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "complete"), shared); //$NON-NLS-1$
        for (String key : shared) {
            assertEquals(apply.get(key), mutate.get(key));
        }
    }

    @Test
    public void applyFormRecipeSuccessPayloadKeySetIsFixed() {
        JsonObject structured = FormResultPayloads.formRecipeSuccess(recipe(HandlerStubReport.empty()));

        assertEquals(8, structured.size());
        assertEquals(List.of(
                "form_fqn", //$NON-NLS-1$
                "attributes_created", //$NON-NLS-1$
                "attributes_updated", //$NON-NLS-1$
                "attributes_removed", //$NON-NLS-1$
                "layout_operations_applied", //$NON-NLS-1$
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "complete"), keys(structured)); //$NON-NLS-1$
    }

    @Test
    public void mutateFormModelSuccessPayloadKeySetIsFixed() {
        JsonObject structured = FormResultPayloads.formModelSuccess(result(HandlerStubReport.empty()));

        assertEquals(5, structured.size());
        assertEquals(List.of(
                "form_fqn", //$NON-NLS-1$
                "operations_applied", //$NON-NLS-1$
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "complete"), keys(structured)); //$NON-NLS-1$
    }

    @Test
    public void mutateFormModelReturnsStructuredPartialFailurePayload() {
        JsonObject structured = FormResultPayloads.partialFailure(partialFailure(
                0, 0, 0, List.of(), List.of()));

        assertEquals(20, structured.size());
        assertEquals(0, structured.get("attributes_created").getAsInt()); //$NON-NLS-1$
        assertTrue(structured.get("partial").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void bothToolsPartialPayloadsHaveIdenticalKeySequence() {
        JsonObject apply = FormResultPayloads.partialFailure(partialFailure(
                1, 2, 3, List.of("Written"), List.of())); //$NON-NLS-1$
        JsonObject mutate = FormResultPayloads.partialFailure(partialFailure(
                0, 0, 0, List.of(), List.of("Skipped"))); //$NON-NLS-1$

        assertEquals(20, apply.size());
        assertEquals(20, mutate.size());
        assertEquals(keys(apply), keys(mutate));
        assertEquals(List.of(
                "code", //$NON-NLS-1$
                "recoverable", //$NON-NLS-1$
                "complete", //$NON-NLS-1$
                "partial", //$NON-NLS-1$
                "form_fqn", //$NON-NLS-1$
                "external_project", //$NON-NLS-1$
                "bm_export_verified_before_stub_phase", //$NON-NLS-1$
                "attributes_created", //$NON-NLS-1$
                "attributes_updated", //$NON-NLS-1$
                "attributes_removed", //$NON-NLS-1$
                "layout_operations_initially_committed", //$NON-NLS-1$
                "handler_slots_initially_committed", //$NON-NLS-1$
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "handler_slots_without_written_stub", //$NON-NLS-1$
                "failure_phase", //$NON-NLS-1$
                "failed_handler", //$NON-NLS-1$
                "rollback_status", //$NON-NLS-1$
                "bm_state", //$NON-NLS-1$
                "serialized_model_state"), keys(apply)); //$NON-NLS-1$
    }

    @Test
    public void noConditionalKeysAppearForEmptyStubReport() {
        HandlerStubReport populated = new HandlerStubReport(List.of("Written"), List.of("Skipped")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(
                keys(FormResultPayloads.formRecipeSuccess(recipe(HandlerStubReport.empty()))),
                keys(FormResultPayloads.formRecipeSuccess(recipe(populated))));
        assertEquals(
                keys(FormResultPayloads.formModelSuccess(result(HandlerStubReport.empty()))),
                keys(FormResultPayloads.formModelSuccess(result(populated))));
        assertEquals(
                keys(FormResultPayloads.partialFailure(partialFailure(0, 0, 0, List.of(), List.of()))),
                keys(FormResultPayloads.partialFailure(partialFailure(
                        0, 0, 0, List.of("Written"), List.of("Skipped"))))); //$NON-NLS-1$ //$NON-NLS-2$
    }

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

    @Test
    public void mutateFormModelCatchesPartialFailureBeforeGenericMetadataFailure() throws Exception {
        String source = Files.readString(locateMutateToolSource());
        int partial = source.indexOf("catch (FormRecipePartialFailureException"); //$NON-NLS-1$
        int generic = source.indexOf("catch (MetadataOperationException"); //$NON-NLS-1$

        assertTrue("partial catch must be present", partial >= 0); //$NON-NLS-1$
        assertTrue("partial catch must precede generic metadata failure", generic > partial); //$NON-NLS-1$
    }

    private static UpdateFormModelResult result(HandlerStubReport report) {
        return new UpdateFormModelResult("Project", "Catalog.C.Form.F", 0, List.of(), report); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static FormRecipeResult recipe(HandlerStubReport report) {
        return new FormRecipeResult(
                "Project", "Catalog.C.Form.F", 1, 2, 3, 4, List.of(), report); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static FormRecipePartialFailureException partialFailure(
            int created,
            int updated,
            int removed,
            List<String> written,
            List<String> skipped) {
        return new FormRecipePartialFailureException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Stub tail failed", //$NON-NLS-1$
                false,
                "Catalog.C.Form.F", //$NON-NLS-1$
                false,
                created,
                updated,
                removed,
                2,
                List.of("Written", "Skipped", "Failed"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                written,
                skipped,
                FailurePhase.WRITE_STUB,
                "Failed", //$NON-NLS-1$
                RollbackStatus.NOT_ATTEMPTED,
                BmState.INITIAL_COMMIT_REMAINS,
                SerializedModelState.INITIAL_EXPORT_VERIFIED,
                null);
    }

    private static List<String> keys(JsonObject object) {
        return new ArrayList<>(object.keySet());
    }

    private static Path locateMutateToolSource() {
        Path moduleRelative = Path.of("..", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "tools", "forms", "MutateFormModelTool.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = Path.of("bundles", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "tools", "forms", "MutateFormModelTool.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(reactorRelative)) {
            return reactorRelative;
        }
        throw new AssertionError("Cannot locate MutateFormModelTool.java from " //$NON-NLS-1$
                + Path.of("").toAbsolutePath()); //$NON-NLS-1$
    }
}
