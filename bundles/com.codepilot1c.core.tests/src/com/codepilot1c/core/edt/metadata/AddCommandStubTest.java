package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator;
import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator.StubText;
import com.codepilot1c.core.edt.forms.BslHandlerStubWriter;
import com.codepilot1c.core.edt.forms.BslHandlerStubWriter.StubWriteOutcome;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.BmState;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.RollbackStatus;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.SerializedModelState;
import com.codepilot1c.core.edt.forms.HandlerStubKind;
import com.codepilot1c.core.edt.forms.ModuleFileWriter;
import com.codepilot1c.core.edt.metadata.EdtMetadataService.RollbackAttempt;

public class AddCommandStubTest {

    private final EdtMetadataService service = new EdtMetadataService(
            new EdtMetadataGateway(), new EventHandlerTargetResolver(target -> List.of()));

    @Test
    public void addCommandRegistersCommandActionPendingStub() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> operation = commandOperation("Run", "RunAction"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> pendingStubs = new ArrayList<>();

        invokeOperations(form, List.of(operation), pendingStubs);

        assertEquals(1, pendingStubs.size());
        Object pending = pendingStubs.get(0);
        assertEquals(HandlerStubKind.COMMAND_ACTION, accessor(pending, "kind")); //$NON-NLS-1$
        assertEquals("RunAction", accessor(pending, "handlerName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Run", accessor(pending, "commandName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, accessor(pending, "eventName")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, accessor(pending, "createdHandlerSlot")); //$NON-NLS-1$
    }

    @Test
    public void commandActionStubIsClientDirectiveWithCommandParameterInBothScriptVariants() {
        BslHandlerStubGenerator generator = new BslHandlerStubGenerator();

        BslHandlerStubGenerator.StubText ru =
                generator.generateCommandAction("RunAction", ScriptVariant.RUSSIAN); //$NON-NLS-1$
        BslHandlerStubGenerator.StubText en =
                generator.generateCommandAction("RunAction", ScriptVariant.ENGLISH); //$NON-NLS-1$

        assertEquals("&НаКлиенте", ru.directive()); //$NON-NLS-1$
        assertEquals("Команда", ru.signatureText()); //$NON-NLS-1$
        assertTrue(ru.procedureText().contains("Процедура RunAction(Команда)")); //$NON-NLS-1$
        assertTrue(ru.procedureText().contains("КонецПроцедуры")); //$NON-NLS-1$
        assertEquals("&AtClient", en.directive()); //$NON-NLS-1$
        assertEquals("Command", en.signatureText()); //$NON-NLS-1$
        assertTrue(en.procedureText().contains("Procedure RunAction(Command)")); //$NON-NLS-1$
        assertTrue(en.procedureText().contains("EndProcedure")); //$NON-NLS-1$
    }

    @Test
    public void commandActionStubDoesNotDeriveDirectiveFromAnyEvent() throws Exception {
        String source = Files.readString(locateGeneratorSource());
        int methodStart = source.indexOf("public StubText generateCommandAction"); //$NON-NLS-1$
        int methodEnd = source.indexOf("private Directive resolveDirective", methodStart); //$NON-NLS-1$
        assertTrue("generateCommandAction end marker not found", methodEnd > methodStart); //$NON-NLS-1$
        String method = source.substring(methodStart, methodEnd);

        assertFalse(method.contains("resolveDirective")); //$NON-NLS-1$
        assertFalse(method.contains("selectWidestParamSet")); //$NON-NLS-1$
        assertFalse(method.contains("Event ")); //$NON-NLS-1$
    }

    @Test
    public void commandActionDefaultsToCommandNameAndStillWritesStub() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> operation = commandOperation("Run", null); //$NON-NLS-1$
        List<Object> pendingStubs = new ArrayList<>();

        invokeOperations(form, List.of(operation), pendingStubs);
        Object pending = pendingStubs.get(0);
        String handlerName = (String)accessor(pending, "handlerName"); //$NON-NLS-1$
        BslHandlerStubGenerator.StubText stub = new BslHandlerStubGenerator()
                .generateCommandAction(handlerName, ScriptVariant.RUSSIAN);

        assertEquals("Run", handlerName); //$NON-NLS-1$
        assertTrue(stub.procedureText().contains("Процедура Run(Команда)")); //$NON-NLS-1$
    }

    @Test
    public void commandStubPathSkipsFreshEventResolution() throws Exception {
        String source = Files.readString(locateServiceSource());
        int methodStart = source.indexOf("private StubPhaseOutcome writeHandlerStubsDetailed"); //$NON-NLS-1$
        int methodEnd = source.indexOf("private StubPhaseFailureException stubPhaseFailure", methodStart); //$NON-NLS-1$
        assertTrue("writeHandlerStubsDetailed end marker not found", methodEnd > methodStart); //$NON-NLS-1$
        String method = source.substring(methodStart, methodEnd);
        int commandStart = method.indexOf("if (pending.kind() == HandlerStubKind.COMMAND_ACTION)"); //$NON-NLS-1$
        int eventStart = method.indexOf("} else {", commandStart); //$NON-NLS-1$
        assertTrue("command-action branch not found", commandStart >= 0 && eventStart > commandStart); //$NON-NLS-1$

        assertFalse(method.substring(commandStart, eventStart).contains("resolveFreshEvent(")); //$NON-NLS-1$
        assertTrue(method.substring(eventStart).contains("resolveFreshEvent(")); //$NON-NLS-1$
    }

    @Test
    public void explicitInvalidActionHandlerNameIsRejectedBeforeMutation() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> operation = commandOperation("Run", "9bad name"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> pendingStubs = new ArrayList<>();

        try {
            invokeOperations(form, List.of(operation), pendingStubs);
            fail("invalid action handler name must be rejected"); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            MetadataOperationException failure = (MetadataOperationException)e.getCause();
            assertEquals(MetadataOperationCode.INVALID_METADATA_NAME, failure.getCode());
        }

        assertTrue(form.getFormCommands().isEmpty());
        assertTrue(pendingStubs.isEmpty());
    }

    @Test
    public void addButtonDoesNotRegisterPendingStub() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> command = commandOperation("Run", "RunAction"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("op", "add_button"); //$NON-NLS-1$ //$NON-NLS-2$
        button.put("name", "RunButton"); //$NON-NLS-1$ //$NON-NLS-2$
        button.put("command_name", "Run"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> pendingStubs = new ArrayList<>();

        invokeOperations(form, List.of(command, button), pendingStubs);

        assertEquals(1, pendingStubs.size());
        assertEquals("RunAction", accessor(pendingStubs.get(0), "handlerName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void pendingStubInvariantsRejectMixedKindPayload() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        List<Object> pendingStubs = new ArrayList<>();
        invokeOperations(form, List.of(commandOperation("Run", "RunAction")), pendingStubs); //$NON-NLS-1$ //$NON-NLS-2$
        Class<?> pendingType = pendingStubs.get(0).getClass();
        Constructor<?> constructor = pendingType.getDeclaredConstructor(
                HandlerStubKind.class,
                Map.class,
                String.class,
                String.class,
                String.class,
                boolean.class);
        constructor.setAccessible(true);

        assertInvariantRejected(constructor, HandlerStubKind.COMMAND_ACTION, "OnOpen", "Run"); //$NON-NLS-1$ //$NON-NLS-2$
        assertInvariantRejected(constructor, HandlerStubKind.EVENT_HANDLER, "OnOpen", "Run"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void addCommandWritesActionStubAfterExportVerification() throws Exception {
        String source = Files.readString(locateServiceSource());
        int methodStart = source.indexOf("public UpdateFormModelResult updateFormModel"); //$NON-NLS-1$
        int methodEnd = source.indexOf("private List<String> writeHandlerStubs", methodStart); //$NON-NLS-1$
        assertTrue("updateFormModel end marker not found", methodEnd > methodStart); //$NON-NLS-1$
        String method = source.substring(methodStart, methodEnd);
        int verify = method.indexOf("verifyObjectPersisted(project, request.formFqn(), opId)"); //$NON-NLS-1$
        int write = method.indexOf("writeHandlerStubs("); //$NON-NLS-1$
        int refresh = method.indexOf("refreshProjectSafely(project)"); //$NON-NLS-1$
        assertTrue("export must be verified before stub write", verify >= 0 && write > verify); //$NON-NLS-1$
        assertTrue("stub write must precede refresh", refresh > write); //$NON-NLS-1$

        List<String> callOrder = new ArrayList<>();
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter() {
            @Override
            public void write(String workspacePath, String content) {
                callOrder.add("write"); //$NON-NLS-1$
                super.write(workspacePath, content);
            }
        };
        writer.seed("Module.bsl", ""); //$NON-NLS-1$ //$NON-NLS-2$
        callOrder.add("model-slot-commit"); //$NON-NLS-1$
        callOrder.add("export"); //$NON-NLS-1$
        callOrder.add("verify"); //$NON-NLS-1$
        StubText stub = new BslHandlerStubGenerator()
                .generateCommandAction("RunAction", ScriptVariant.RUSSIAN); //$NON-NLS-1$
        StubWriteOutcome outcome = new BslHandlerStubWriter(writer)
                .write("Module.bsl", "RunAction", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(StubWriteOutcome.WRITTEN, outcome);
        assertTrue(callOrder.indexOf("write") > callOrder.indexOf("verify")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void existingActionProcedureIsSkippedWithWarnAndSlotIsKept() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        List<Object> pendingStubs = new ArrayList<>();
        invokeOperations(form, List.of(commandOperation("Run", "RunAction")), pendingStubs); //$NON-NLS-1$ //$NON-NLS-2$
        String existing = "&НаКлиенте\nПроцедура RunAction(Команда)\n\tСообщить(\"готово\");\nКонецПроцедуры"; //$NON-NLS-1$
        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        writer.seed("Module.bsl", existing); //$NON-NLS-1$
        StubText stub = new BslHandlerStubGenerator()
                .generateCommandAction("RunAction", ScriptVariant.RUSSIAN); //$NON-NLS-1$

        StubWriteOutcome outcome = new BslHandlerStubWriter(writer)
                .write("Module.bsl", "RunAction", stub, ScriptVariant.RUSSIAN); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(StubWriteOutcome.SKIPPED_EXISTING_WARN, outcome);
        assertEquals(existing, writer.read("Module.bsl")); //$NON-NLS-1$
        assertEquals(1, form.getFormCommands().size());
        assertEquals("Run", form.getFormCommands().get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void duplicateHandlerAcrossCommandAndEventWritesSingleStub() throws Exception {
        Event onOpen = McoreFactory.eINSTANCE.createEvent();
        onOpen.setName("OnOpen"); //$NON-NLS-1$
        onOpen.setNameRu("ПриОткрытии"); //$NON-NLS-1$
        onOpen.setEnvironments(Environments.ALL_CLIENTS);
        EdtMetadataService eventService = new EdtMetadataService(
                new EdtMetadataGateway(), new EventHandlerTargetResolver(target -> List.of(onOpen)));
        Form form = FormFactory.eINSTANCE.createForm();
        Map<String, Object> eventOperation = new LinkedHashMap<>();
        eventOperation.put("op", "add_event_handler"); //$NON-NLS-1$ //$NON-NLS-2$
        eventOperation.put("target", "form"); //$NON-NLS-1$ //$NON-NLS-2$
        eventOperation.put("event", "OnOpen"); //$NON-NLS-1$ //$NON-NLS-2$
        eventOperation.put("handler_name", "SharedAction"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Object> pendingStubs = new ArrayList<>();
        invokeOperations(
                eventService,
                form,
                List.of(commandOperation("Run", "SharedAction"), eventOperation), //$NON-NLS-1$ //$NON-NLS-2$
                pendingStubs);
        assertEquals(2, pendingStubs.size());

        InMemoryModuleFileWriter writer = new InMemoryModuleFileWriter();
        writer.seed("Module.bsl", ""); //$NON-NLS-1$ //$NON-NLS-2$
        BslHandlerStubWriter stubWriter = new BslHandlerStubWriter(writer);
        BslHandlerStubGenerator generator = new BslHandlerStubGenerator();
        StubWriteOutcome first = stubWriter.write(
                "Module.bsl", //$NON-NLS-1$
                "SharedAction", //$NON-NLS-1$
                generator.generateCommandAction("SharedAction", ScriptVariant.RUSSIAN), //$NON-NLS-1$
                ScriptVariant.RUSSIAN);
        StubWriteOutcome second = stubWriter.write(
                "Module.bsl", //$NON-NLS-1$
                "SharedAction", //$NON-NLS-1$
                generator.generate(onOpen, "SharedAction", ScriptVariant.RUSSIAN), //$NON-NLS-1$
                ScriptVariant.RUSSIAN);

        assertEquals(StubWriteOutcome.WRITTEN, first);
        assertEquals(StubWriteOutcome.SKIPPED_EXISTING_WARN, second);
        assertEquals(1, countOccurrences(writer.read("Module.bsl"), "Процедура SharedAction(")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void writeFailureRollsBackCommandSlotAndReportsMeasuredState() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        List<Object> pendingStubs = new ArrayList<>();
        invokeOperations(form, List.of(commandOperation("Run", "RunAction")), pendingStubs); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, form.getFormCommands().size());
        AtomicBoolean compensationCalled = new AtomicBoolean();
        RollbackAttempt measured = new RollbackAttempt(
                RollbackStatus.REMOVED_AND_EXPORTED,
                BmState.HANDLER_REMOVED,
                SerializedModelState.ROLLBACK_EXPORT_VERIFIED,
                null);

        RollbackAttempt actual = service.compensateHandlerSlot(true, "RunAction", "test", () -> { //$NON-NLS-1$ //$NON-NLS-2$
            compensationCalled.set(true);
            form.getFormCommands().clear();
            return measured;
        });

        assertTrue(compensationCalled.get());
        assertTrue(form.getFormCommands().isEmpty());
        assertEquals(RollbackStatus.REMOVED_AND_EXPORTED, actual.status());
        assertTrue(service.stubWriteFailure("RunAction", actual).getMessage() //$NON-NLS-1$
                .contains("rollback_status=REMOVED_AND_EXPORTED")); //$NON-NLS-1$
    }

    @Test
    public void referencedCommandIsNotRemovedAndReportsNotAttemptedUnsafe() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("Run"); //$NON-NLS-1$
        form.getFormCommands().add(command);
        Button button = FormFactory.eINSTANCE.createButton();
        button.setName("RunButton"); //$NON-NLS-1$
        button.setCommandName(command);
        form.getItems().add(button);

        assertTrue(service.isCommandReferenced(form, command));
        Class<?> mutationType = null;
        for (Class<?> nested : EdtMetadataService.class.getDeclaredClasses()) {
            if ("RollbackMutationResult".equals(nested.getSimpleName())) { //$NON-NLS-1$
                mutationType = nested;
                break;
            }
        }
        assertTrue("RollbackMutationResult not found", mutationType != null); //$NON-NLS-1$
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Object referenced = Enum.valueOf((Class)mutationType, "SLOT_REFERENCED"); //$NON-NLS-1$
        Method mapping = EdtMetadataService.class.getDeclaredMethod(
                "mapCommandRollbackMutation", mutationType, String.class, String.class); //$NON-NLS-1$
        mapping.setAccessible(true);
        Object attempt = mapping.invoke(service, referenced, "Run", "test"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(RollbackStatus.NOT_ATTEMPTED_UNSAFE, accessor(attempt, "status")); //$NON-NLS-1$
        assertEquals(BmState.INITIAL_COMMIT_REMAINS, accessor(attempt, "bmState")); //$NON-NLS-1$
        assertEquals(1, form.getFormCommands().size());
        assertEquals(command, button.getCommandName());
    }

    private void invokeOperations(Form form, List<Map<String, Object>> operations, List<Object> pendingStubs)
            throws Exception {
        invokeOperations(service, form, operations, pendingStubs);
    }

    private static void invokeOperations(
            EdtMetadataService targetService,
            Form form,
            List<Map<String, Object>> operations,
            List<Object> pendingStubs) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
        method.setAccessible(true);
        method.invoke(targetService, form, operations, pendingStubs);
    }

    private static Object accessor(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Map<String, Object> commandOperation(String name, String action) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "add_command"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("name", name); //$NON-NLS-1$
        if (action != null) {
            operation.put("action", action); //$NON-NLS-1$
        }
        return operation;
    }

    private static void assertInvariantRejected(
            Constructor<?> constructor, HandlerStubKind kind, String eventName, String commandName) throws Exception {
        try {
            constructor.newInstance(kind, Map.of(), eventName, commandName, "Handler", true); //$NON-NLS-1$
            fail("mixed pending-stub payload must be rejected"); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Path locateGeneratorSource() {
        Path moduleRelative = Path.of("..", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "forms", "BslHandlerStubGenerator.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = Path.of("bundles", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "forms", "BslHandlerStubGenerator.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(reactorRelative)) {
            return reactorRelative;
        }
        throw new AssertionError("Cannot locate BslHandlerStubGenerator.java from " //$NON-NLS-1$
                + Path.of("").toAbsolutePath()); //$NON-NLS-1$
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
}
