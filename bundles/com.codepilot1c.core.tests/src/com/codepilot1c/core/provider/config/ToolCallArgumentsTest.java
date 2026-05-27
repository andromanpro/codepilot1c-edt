package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
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
}
