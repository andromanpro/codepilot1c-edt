package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edt.ast.MetadataIndexResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ScanMetadataIndexToolContractTest {

    @Test
    public void schemaPublishesStrictOffsetAndLimitBounds() {
        ScanMetadataIndexTool tool = new ScanMetadataIndexTool();
        JsonObject schema = JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        JsonObject limit = properties.getAsJsonObject("limit"); //$NON-NLS-1$
        JsonObject offset = properties.getAsJsonObject("offset"); //$NON-NLS-1$

        assertEquals("object", schema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(schema.get("additionalProperties").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, limit.get("minimum").getAsInt()); //$NON-NLS-1$
        assertEquals(1000, limit.get("maximum").getAsInt()); //$NON-NLS-1$
        assertEquals(200, limit.get("default").getAsInt()); //$NON-NLS-1$
        assertEquals(0, offset.get("minimum").getAsInt()); //$NON-NLS-1$
        assertEquals(0, offset.get("default").getAsInt()); //$NON-NLS-1$
        assertTrue(offset.get("description").getAsString().contains("nextOffset")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(tool.getDescription().contains("nextOffset")); //$NON-NLS-1$
    }

    @Test
    public void resultSerializationIncludesPageNavigationFields() {
        MetadataIndexResult result = new MetadataIndexResult(
                "Demo", "edt_configuration_scan", "all", "ru", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                5, 2, 2, true, 4, List.of());

        JsonObject json = new Gson().toJsonTree(result).getAsJsonObject();
        assertEquals(5, json.get("total").getAsInt()); //$NON-NLS-1$
        assertEquals(2, json.get("offset").getAsInt()); //$NON-NLS-1$
        assertEquals(2, json.get("returned").getAsInt()); //$NON-NLS-1$
        assertTrue(json.get("hasMore").getAsBoolean()); //$NON-NLS-1$
        assertEquals(4, json.get("nextOffset").getAsInt()); //$NON-NLS-1$

        MetadataIndexResult legacy = new MetadataIndexResult(
                "Demo", "edt_configuration_scan", "all", "ru", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                5, 2, true, List.of());
        assertEquals(0, legacy.getOffset());
        assertEquals(2, legacy.getNextOffset());
    }
}
