package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.Test;

import com.google.gson.JsonObject;

public class ResolveWebClientUrlToolTest {

    @Test
    public void multimodalGuardPublishesOnlyProviderNeutralAssumption() throws Exception {
        ResolveWebClientUrlTool tool = new ResolveWebClientUrlTool();
        Method guard = ResolveWebClientUrlTool.class
                .getDeclaredMethod("applyMultimodalGuard", JsonObject.class); //$NON-NLS-1$
        guard.setAccessible(true);
        JsonObject data = new JsonObject();

        guard.invoke(tool, data);

        assertFalse(data.has("active_model")); //$NON-NLS-1$
        assertEquals(Set.of("vision_confirmed", "vision_basis", "vision_hint"), data.keySet()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(data.get("vision_confirmed").getAsBoolean()); //$NON-NLS-1$
        assertEquals("assumed-multimodal", data.get("vision_basis").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", data.get("vision_hint").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
