package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

import com.codepilot1c.core.edt.forms.BslHandlerStubGenerator;

public class AddCommandStubTest {

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
}
