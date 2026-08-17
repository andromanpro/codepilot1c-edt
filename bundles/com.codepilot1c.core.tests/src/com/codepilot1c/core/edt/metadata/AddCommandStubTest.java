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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;
import com.codepilot1c.core.edt.forms.HandlerStubKind;

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

    private void invokeOperations(Form form, List<Map<String, Object>> operations, List<Object> pendingStubs)
            throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
        method.setAccessible(true);
        method.invoke(service, form, operations, pendingStubs);
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
}
