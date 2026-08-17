package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.DelegationClamp;
import com.codepilot1c.core.agent.profiles.ProfileRouter;

public class DelegateToAgentToolDelegationClampTest extends DelegationToolTestSupport {

    @Test
    public void wrapperPreservesContextAndRemapsDenialToolName() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        ProfileRouter router = new ProfileRouter();
        TaskTool taskTool = new TaskTool(placeholderRegistry(), router, executor);
        DelegateToAgentTool tool = new DelegateToAgentTool(taskTool, router);

        ToolResult result = tool.execute(
                Map.of("agentType", "metadata", "task", "Создай справочник"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("plan", 0)).join(); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertNull(executor.config);
        assertEquals("delegation_denied", result.getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("delegate_to_agent", result.getStructuredString("tool")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DelegationClamp.REASON_CAPABILITY_EXCEEDED,
                result.getStructuredString("reason_code")); //$NON-NLS-1$
        assertEquals("plan", result.getStructuredString("parent_profile")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
