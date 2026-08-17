package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.BmState;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.FailurePhase;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.RollbackStatus;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.SerializedModelState;

public class FormModelPartialFailureTest {

    @Test
    public void updateFormModelWrapsStubPhaseFailureIntoPartialException() throws Exception {
        String source = Files.readString(locateServiceSource());
        int start = source.indexOf("public UpdateFormModelResult updateFormModel"); //$NON-NLS-1$
        int end = source.indexOf("private StubPhaseOutcome writeHandlerStubsDetailed", start); //$NON-NLS-1$
        assertTrue("updateFormModel end marker not found", end > start); //$NON-NLS-1$
        String method = source.substring(start, end);

        int catchIndex = method.indexOf("catch (MetadataOperationException e)"); //$NON-NLS-1$
        int refreshIndex = method.indexOf("refreshProjectSafely(project)"); //$NON-NLS-1$
        assertTrue(catchIndex >= 0);
        assertTrue(method.contains("StubPhaseFailureException")); //$NON-NLS-1$
        assertTrue(method.contains("formRecipePartialFailure(")); //$NON-NLS-1$
        assertTrue(refreshIndex > catchIndex);
    }

    @Test
    public void formModelPartialFailureReportsZeroAttributeCounters() {
        MetadataOperationException cause = new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Stub write failed", //$NON-NLS-1$
                false);

        FormRecipePartialFailureException failure = EdtMetadataService.formRecipePartialFailure(
                cause,
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                false,
                0,
                0,
                0,
                2,
                List.of("Written", "Failed"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of("Written"), //$NON-NLS-1$
                List.of(),
                FailurePhase.WRITE_STUB,
                "Failed", //$NON-NLS-1$
                RollbackStatus.NOT_ATTEMPTED,
                BmState.INITIAL_COMMIT_REMAINS,
                SerializedModelState.INITIAL_EXPORT_VERIFIED);

        assertTrue(failure.attributesCreated() == 0);
        assertTrue(failure.attributesUpdated() == 0);
        assertTrue(failure.attributesRemoved() == 0);
        assertTrue(failure.handlerSlotsWithoutWrittenStub().contains("Failed")); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains(
                "partial_state={form_fqn=Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void updateFormModelCountsModelOperationsOnly() throws Exception {
        String source = Files.readString(locateServiceSource());
        int start = source.indexOf("public UpdateFormModelResult updateFormModel"); //$NON-NLS-1$
        int end = source.indexOf("private StubPhaseOutcome writeHandlerStubsDetailed", start); //$NON-NLS-1$
        assertTrue("updateFormModel end marker not found", end > start); //$NON-NLS-1$
        String method = source.substring(start, end);

        assertFalse(method.contains("operationSummaries.addAll(")); //$NON-NLS-1$
        assertFalse(method.contains("new ArrayList<>(operationSummaries)")); //$NON-NLS-1$
    }

    private static Path locateServiceSource() {
        Path moduleRelative = Path.of("..", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "metadata", "EdtMetadataService.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = Path.of("bundles", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "metadata", "EdtMetadataService.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(reactorRelative)) {
            return reactorRelative;
        }
        throw new AssertionError("Cannot locate EdtMetadataService.java from " //$NON-NLS-1$
                + Path.of("").toAbsolutePath()); //$NON-NLS-1$
    }
}
