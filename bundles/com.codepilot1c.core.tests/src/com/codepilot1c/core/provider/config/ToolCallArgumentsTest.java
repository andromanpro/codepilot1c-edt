package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public class ToolCallArgumentsTest {

    @Test
    public void acceptsJsonObjectString() {
        assertEquals("{\"path\":\"Code.md\"}", //$NON-NLS-1$
                ToolCallArguments.normalize("{\"path\":\"Code.md\"}").orElseThrow()); //$NON-NLS-1$
    }

    @Test
    public void blankInputNormalizesToEmptyObject() {
        assertEquals("{}", ToolCallArguments.normalize("").orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void repairsTruncatedObjectWhenPossible() {
        assertEquals("{\"content\":\"abc\"}", //$NON-NLS-1$
                ToolCallArguments.normalize("{\"content\":\"abc").orElseThrow()); //$NON-NLS-1$
    }

    @Test
    public void rejectsJsonArrayAndPrimitiveStrings() {
        assertTrue(ToolCallArguments.normalize("[1,2]").isEmpty()); //$NON-NLS-1$
        assertTrue(ToolCallArguments.normalize("\"text\"").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void acceptsJsonObjectElement() {
        JsonObject object = new JsonObject();
        object.addProperty("operation", "status"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("{\"operation\":\"status\"}", //$NON-NLS-1$
                ToolCallArguments.normalize(object).orElseThrow());
    }

    @Test
    public void rejectsJsonPrimitiveElement() {
        assertTrue(ToolCallArguments.normalize(new JsonPrimitive("not an object")).isEmpty()); //$NON-NLS-1$
    }

    // --- repaired-status semantics: actual JsonRepairUtil salvage vs canonicalization ---

    @Test
    public void validJsonIsNotMarkedRepaired() {
        ToolCallArguments.Normalized result =
                ToolCallArguments.normalizeWithStatus("{\"path\":\"Code.md\"}").orElseThrow(); //$NON-NLS-1$
        assertFalse(result.repaired());
    }

    @Test
    public void prettyPrintedJsonIsNotMarkedRepaired() {
        ToolCallArguments.Normalized result = ToolCallArguments.normalizeWithStatus(
                "{\n  \"path\": \"Code.md\",\n  \"old_text\": \"a\"\n}").orElseThrow(); //$NON-NLS-1$
        assertFalse(result.repaired());
        assertTrue(result.json().contains("\"old_text\":\"a\"")); //$NON-NLS-1$
    }

    @Test
    public void truncationCutAfterContentQuoteRepairsToEmptyContentAndIsMarked() {
        // The exact zero-byte data-loss scenario: stream cut right after "content":"
        ToolCallArguments.Normalized result = ToolCallArguments.normalizeWithStatus(
                "{\"path\":\"ДО/src/CommonModules/аи_АртельИнтеграция/Module.bsl\",\"content\":\"").orElseThrow(); //$NON-NLS-1$
        assertTrue(result.repaired());
        assertTrue(result.json().contains("\"content\":\"\"")); //$NON-NLS-1$
    }

    @Test
    public void truncationMidContentIsMarkedRepaired() {
        ToolCallArguments.Normalized result = ToolCallArguments.normalizeWithStatus(
                "{\"path\":\"Module.bsl\",\"content\":\"Процедура Тест()\\nЧасть файла").orElseThrow(); //$NON-NLS-1$
        assertTrue(result.repaired());
    }

    @Test
    public void truncationAfterColonIsMarkedRepaired() {
        ToolCallArguments.Normalized result = ToolCallArguments.normalizeWithStatus(
                "{\"path\":\"Module.bsl\",\"content\":").orElseThrow(); //$NON-NLS-1$
        assertTrue(result.repaired());
    }

    @Test
    public void truncationMidNewTextIsMarkedRepaired() {
        ToolCallArguments.Normalized result = ToolCallArguments.normalizeWithStatus(
                "{\"path\":\"Module.bsl\",\"old_text\":\"Функция Старая()\",\"new_text\":\"Функция Новая()\\n  // обрезано").orElseThrow(); //$NON-NLS-1$
        assertTrue(result.repaired());
    }

    @Test
    public void unclosedBraceOnlyIsMarkedRepaired() {
        ToolCallArguments.Normalized result = ToolCallArguments.normalizeWithStatus(
                "{\"path\":\"Module.bsl\",\"old_text\":\"Функция Старая()\"").orElseThrow(); //$NON-NLS-1$
        assertTrue(result.repaired());
    }

    @Test
    public void blankInputIsNotMarkedRepaired() {
        ToolCallArguments.Normalized result = ToolCallArguments.normalizeWithStatus("").orElseThrow(); //$NON-NLS-1$
        assertEquals("{}", result.json()); //$NON-NLS-1$
        assertFalse(result.repaired());
    }
}
