package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.observability.InfobaseLockService;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ToolPayloadContractTest {

    @Test
    public void getInfobaseLocksMissingPathUsesJsonErrorEnvelope() {
        ToolResult result = new GetInfobaseLocksTool(new InfobaseLockService(new FakeGateway(), new FakeRunner()))
                .execute(Map.of()).join();

        assertFalse(result.isSuccess());
        assertErrorEnvelope(json(result), "get_infobase_locks", "INVALID_ARGUMENT"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject json(ToolResult result) {
        String payload = result.isSuccess() ? result.getContent() : result.getErrorMessage();
        return JsonParser.parseString(payload).getAsJsonObject();
    }

    private static void assertErrorEnvelope(JsonObject json, String tool, String code) {
        assertEquals("error", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(tool, json.get("tool").getAsString()); //$NON-NLS-1$
        assertEquals(code, json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue(json.has("op_id")); //$NON-NLS-1$
        assertTrue(json.has("message")); //$NON-NLS-1$
        assertTrue(json.has("recoverable")); //$NON-NLS-1$
        assertTrue(json.has("details")); //$NON-NLS-1$
    }
}
