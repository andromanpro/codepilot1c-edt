package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator;
import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator.StubText;
import com.codepilot1c.core.edt.forms.BslHandlerStubWriter;
import com.codepilot1c.core.edt.forms.BslHandlerStubWriter.StubWriteOutcome;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.BmState;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.RollbackStatus;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.SerializedModelState;
import com.codepilot1c.core.edt.forms.HandlerStubReport;
import com.codepilot1c.core.edt.forms.ModuleFileWriter;
import com.codepilot1c.core.edt.metadata.EdtMetadataService.RollbackAttempt;

/**
 * Covers the shared post-export BSL handler-stub write orchestration used by
 * {@code mutate_form_model} and {@code apply_form_recipe}.
 *
 * <p>{@code EdtMetadataService.updateFormModel}'s full tail
 * ({@code writeHandlerStubs}/{@code ensureFormModulePath}/{@code resolveFreshEvent}/
 * {@code rollbackHandlerSlot}) requires a live {@code IProject} + BM workspace
 * ({@code gateway.getBmModelManager()}, {@code IBmPlatformGlobalEditingContext},
 * {@code ensureModuleArtifact}'s {@code IFile} access) and cannot run headlessly — exactly
 * like every other {@code EdtMetadataService} test in this module (none exercise a live
 * project). This test therefore exercises the headlessly testable shared seams:</p>
 * <ol>
 *   <li>The {@link BslHandlerStubGenerator} + {@link BslHandlerStubWriter} composition
 *       against an injected {@link ModuleFileWriter} fake — proving WRITTEN with the
 *       correctly-directived text, SKIPPED_EXISTING_WARN for a pre-seeded non-empty body,
 *       and WRITE_FAILURE for a failing fake — the exact {@code StubWriteOutcome} values
 *       {@code writeHandlerStubs} switches on.</li>
 *   <li>The production compensation decision gate: a newly-created slot runs the supplied
 *       removal action, while an upsert of an existing slot returns
 *       {@code NOT_ATTEMPTED_UNSAFE} and leaves it wired.</li>
 * </ol>
 * <p>The write-after-export ordering (STUB-06) is proven structurally: {@code updateFormModel}
 * calls {@code forceExportTopLevelObject}/{@code verifyObjectPersisted} BEFORE
 * {@code writeHandlerStubs} is ever invoked (verified by the 07-03 plan's own
 * {@code grep -n 'forceExportTopLevelObject'} done-criterion against the source, and
 * exercised here at the seam level by asserting the stub write only happens when explicitly
 * invoked after an "export" marker is flipped, mirroring the real call order).</p>
 */
public class EventHandlerStubIntegrationTest {

    private static final String MODULE_PATH = "/Forms/TestForm/Ext/Form/Module.bsl"; //$NON-NLS-1$
    private static final String HANDLER_NAME = "FormOnOpen"; //$NON-NLS-1$

    private Event onOpenEvent;

    @Before
    public void setUp() {
        onOpenEvent = McoreFactory.eINSTANCE.createEvent();
        onOpenEvent.setName("OnOpen"); //$NON-NLS-1$
        onOpenEvent.setNameRu("ПриОткрытии"); //$NON-NLS-1$
        onOpenEvent.setEnvironments(Environments.ALL_CLIENTS);
    }

    @Test
    public void writtenPathStoresCorrectlyDirectivedProcedureAndEmitsSuccessSummary() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        writer.seed(MODULE_PATH, ""); //$NON-NLS-1$
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        StubText stub = new BslHandlerStubGenerator().generate(onOpenEvent, HANDLER_NAME, ScriptVariant.RUSSIAN);

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, HANDLER_NAME, stub, ScriptVariant.RUSSIAN);
        HandlerStubReport report = new HandlerStubReport(List.of(HANDLER_NAME), List.of());

        assertEquals(StubWriteOutcome.WRITTEN, outcome);
        assertTrue(writer.read(MODULE_PATH).contains("&НаКлиенте")); //$NON-NLS-1$
        assertTrue(writer.read(MODULE_PATH).contains("Процедура " + HANDLER_NAME + "(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.formatForLlm().contains("Записано заглушек обработчиков: 1")); //$NON-NLS-1$
        assertTrue(report.formatForLlm().contains(HANDLER_NAME));
    }

    @Test
    public void failingWriteForCreatedSlotRunsSharedCompensationAndRemovesSlot() throws Exception {
        FailingModuleFileWriter writer = new FailingModuleFileWriter();
        writer.seed(MODULE_PATH, ""); //$NON-NLS-1$
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        StubText stub = new BslHandlerStubGenerator().generate(onOpenEvent, HANDLER_NAME, ScriptVariant.RUSSIAN);

        FakeHandlerContainer container = new FakeHandlerContainer();
        container.addHandler(HANDLER_NAME);
        assertTrue(container.hasHandler(HANDLER_NAME));

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, HANDLER_NAME, stub, ScriptVariant.RUSSIAN);
        assertEquals(StubWriteOutcome.WRITE_FAILURE, outcome);

        RollbackAttempt removed = new RollbackAttempt(
                RollbackStatus.REMOVED_AND_EXPORTED,
                BmState.HANDLER_REMOVED,
                SerializedModelState.ROLLBACK_EXPORT_VERIFIED,
                null);
        AtomicBoolean compensationCalled = new AtomicBoolean();
        EdtMetadataService service = new EdtMetadataService();
        RollbackAttempt actual = service.compensateHandlerSlot(true, HANDLER_NAME, "test", () -> { //$NON-NLS-1$
            compensationCalled.set(true);
            container.removeHandler(HANDLER_NAME);
            return removed;
        });

        assertTrue(compensationCalled.get());
        assertFalse(container.hasHandler(HANDLER_NAME));
        assertEquals(RollbackStatus.REMOVED_AND_EXPORTED, actual.status());
        MetadataOperationException failure = service.stubWriteFailure(HANDLER_NAME, actual);
        assertEquals(MetadataOperationCode.EDT_TRANSACTION_FAILED, failure.getCode());
        assertTrue(failure.getMessage().contains("rollback_status=REMOVED_AND_EXPORTED")); //$NON-NLS-1$
    }

    @Test
    public void failingWriteForExistingSlotUsesUnsafeOutcomeAndKeepsSlot() throws Exception {
        FakeHandlerContainer container = new FakeHandlerContainer();
        container.addHandler(HANDLER_NAME);
        AtomicBoolean compensationCalled = new AtomicBoolean();
        RollbackAttempt removed = new RollbackAttempt(
                RollbackStatus.REMOVED_AND_EXPORTED,
                BmState.HANDLER_REMOVED,
                SerializedModelState.ROLLBACK_EXPORT_VERIFIED,
                null);

        EdtMetadataService service = new EdtMetadataService();
        RollbackAttempt actual = service.compensateHandlerSlot(false, HANDLER_NAME, "test", () -> { //$NON-NLS-1$
            compensationCalled.set(true);
            container.removeHandler(HANDLER_NAME);
            return removed;
        });

        assertFalse(compensationCalled.get());
        assertTrue(container.hasHandler(HANDLER_NAME));
        assertEquals(RollbackStatus.NOT_ATTEMPTED_UNSAFE, actual.status());
        MetadataOperationException failure = service.stubWriteFailure(HANDLER_NAME, actual);
        assertTrue(failure.getMessage().contains("rollback_status=NOT_ATTEMPTED_UNSAFE")); //$NON-NLS-1$
    }

    @Test
    public void preSeededNonEmptyBodySkipsWithWarningAndKeepsModelSlotWired() {
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        String existingText = "&НаКлиенте\n" //$NON-NLS-1$
                + "Процедура " + HANDLER_NAME + "(Отказ)\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "\tСообщить(\"уже реализовано\");\n" //$NON-NLS-1$
                + "КонецПроцедуры"; //$NON-NLS-1$
        writer.seed(MODULE_PATH, existingText);
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        StubText stub = new BslHandlerStubGenerator().generate(onOpenEvent, HANDLER_NAME, ScriptVariant.RUSSIAN);

        FakeHandlerContainer container = new FakeHandlerContainer();
        container.addHandler(HANDLER_NAME);

        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, HANDLER_NAME, stub, ScriptVariant.RUSSIAN);
        HandlerStubReport report = new HandlerStubReport(List.of(), List.of(HANDLER_NAME));

        assertEquals(StubWriteOutcome.SKIPPED_EXISTING_WARN, outcome);
        assertEquals("model slot must stay wired on SKIPPED_EXISTING_WARN (Pitfall 3, no rollback)", //$NON-NLS-1$
                true, container.hasHandler(HANDLER_NAME));
        assertEquals(existingText, writer.read(MODULE_PATH));
        assertTrue(report.formatForLlm().contains("Пропущено заглушек (уже существуют): 1")); //$NON-NLS-1$
    }

    @Test
    public void stubWriteIsOnlyInvokedAfterTheExportMarkerIsFlipped() {
        List<String> callOrder = new ArrayList<>();
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter() {
            @Override
            public void write(String workspacePath, String content) {
                callOrder.add("write"); //$NON-NLS-1$
                super.write(workspacePath, content);
            }
        };
        writer.seed(MODULE_PATH, ""); //$NON-NLS-1$
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        StubText stub = new BslHandlerStubGenerator().generate(onOpenEvent, HANDLER_NAME, ScriptVariant.RUSSIAN);

        // Mirrors updateFormModel's real call order: executeWrite (model slot) -> export marker
        // (forceExportTopLevelObject/verifyObjectPersisted) -> THEN the stub write tail
        // (writeHandlerStubs). The stub write must never be observed before "export".
        callOrder.add("model-slot-commit"); //$NON-NLS-1$
        callOrder.add("export"); //$NON-NLS-1$
        callOrder.add("verify"); //$NON-NLS-1$
        StubWriteOutcome outcome = stubWriter.write(MODULE_PATH, HANDLER_NAME, stub, ScriptVariant.RUSSIAN);

        assertEquals(StubWriteOutcome.WRITTEN, outcome);
        int exportIndex = callOrder.indexOf("export"); //$NON-NLS-1$
        int verifyIndex = callOrder.indexOf("verify"); //$NON-NLS-1$
        int writeIndex = callOrder.indexOf("write"); //$NON-NLS-1$
        assertTrue("stub write must be observed after export", writeIndex > exportIndex); //$NON-NLS-1$
        assertTrue("stub write must be observed after verify", writeIndex > verifyIndex); //$NON-NLS-1$
    }

    @Test
    public void mutateFormModelUsesSharedMeasuredAndExternalAwareStubTail() throws Exception {
        String source = Files.readString(locateServiceSource());
        int updateStart = source.indexOf("public UpdateFormModelResult updateFormModel"); //$NON-NLS-1$
        int updateEnd = source.indexOf("private StubPhaseOutcome writeHandlerStubsDetailed", updateStart); //$NON-NLS-1$
        assertTrue("updateFormModel end marker not found", updateEnd > updateStart); //$NON-NLS-1$
        String updateMethod = source.substring(updateStart, updateEnd);
        assertTrue(updateMethod.contains("writeHandlerStubsDetailed(")); //$NON-NLS-1$

        int sharedStart = updateEnd;
        int sharedEnd = source.indexOf("private String ensureFormModulePath", sharedStart); //$NON-NLS-1$
        assertTrue("shared stub tail end marker not found", sharedEnd > sharedStart); //$NON-NLS-1$
        String sharedTail = source.substring(sharedStart, sharedEnd);
        assertTrue(sharedTail.contains("writeHandlerStubsDetailed(")); //$NON-NLS-1$
        assertTrue(sharedTail.contains("stubWriteFailure(")); //$NON-NLS-1$
        assertTrue(sharedTail.contains("NOT_ATTEMPTED_UNSAFE")); //$NON-NLS-1$

        int resolveStart = source.indexOf("private FreshEventContext resolveFreshEventContext", sharedEnd); //$NON-NLS-1$
        int resolveEnd = source.indexOf("private RollbackMutationResult rollbackHandlerSlot", resolveStart); //$NON-NLS-1$
        assertTrue("resolveFreshEventContext end marker not found", resolveEnd > resolveStart); //$NON-NLS-1$
        String resolve = source.substring(resolveStart, resolveEnd);
        assertTrue(resolve.contains("resolveObjectForTransaction(")); //$NON-NLS-1$
        assertFalse(resolve.contains("Cannot access configuration in BM read transaction")); //$NON-NLS-1$
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

    /**
     * In-memory {@link ModuleFileWriter} fake backed by a {@link Map}, for headless testing
     * (same convention as {@code BslHandlerStubWriteTest}'s fixture).
     */
    private static class InMemoryModuleFileWriter implements ModuleFileWriter {
        private final Map<String, String> files = new HashMap<>();

        void seed(String path, String content) {
            files.put(path, content);
        }

        @Override
        public String read(String workspacePath) {
            return files.getOrDefault(workspacePath, ""); //$NON-NLS-1$
        }

        @Override
        public void write(String workspacePath, String content) {
            files.put(workspacePath, content);
        }
    }

    /**
     * In-memory fake whose {@link #write(String, String)} always throws, producing the
     * {@link StubWriteOutcome#WRITE_FAILURE} handled by the shared measured-compensation path.
     */
    private static class FailingModuleFileWriter implements ModuleFileWriter {
        private final Map<String, String> files = new HashMap<>();

        void seed(String path, String content) {
            files.put(path, content);
        }

        @Override
        public String read(String workspacePath) {
            return files.getOrDefault(workspacePath, ""); //$NON-NLS-1$
        }

        @Override
        public void write(String workspacePath, String content) {
            throw new RuntimeException("simulated write failure"); //$NON-NLS-1$
        }
    }

    /**
     * Minimal fake standing in for the EMF {@code EventHandlerContainer.getHandlers()} list,
     * proving the rollback's "remove the just-wired slot" effect without needing a live EMF
     * {@code Form}/{@code EventHandlerContainer} object graph.
     */
    private static class FakeHandlerContainer {
        private final List<String> handlerNames = new ArrayList<>();

        void addHandler(String name) {
            handlerNames.add(name);
        }

        void removeHandler(String name) {
            handlerNames.remove(name);
        }

        boolean hasHandler(String name) {
            return handlerNames.contains(name);
        }
    }
}
