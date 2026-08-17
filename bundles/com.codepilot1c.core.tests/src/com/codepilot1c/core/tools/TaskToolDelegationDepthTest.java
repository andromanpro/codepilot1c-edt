package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.profiles.DelegationClamp;
import com.codepilot1c.core.agent.profiles.ProfileRouter;

public class TaskToolDelegationDepthTest extends DelegationToolTestSupport {

    @Test
    public void depthLimitSurvivesThreadPoolHops() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        TaskTool tool = new TaskTool(placeholderRegistry(), new ProfileRouter(), executor);
        ExecutorService callerPool = Executors.newSingleThreadExecutor();
        try {
            ToolResult result = CompletableFuture.supplyAsync(
                    () -> tool.execute(
                            Map.of("prompt", "Продолжи", "profile", "code"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                            context("code", 3)).join(), //$NON-NLS-1$
                    callerPool).join();

            assertFalse(result.isSuccess());
            assertNull(executor.config);
            assertEquals(DelegationClamp.REASON_DEPTH_EXCEEDED,
                    result.getStructuredString("reason_code")); //$NON-NLS-1$
        } finally {
            callerPool.shutdownNow();
        }
    }

    @Test
    public void maxDepthChildCannotDelegateThroughEitherWrapper() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        TaskTool tool = new TaskTool(placeholderRegistry(), new ProfileRouter(), executor);

        ToolResult result = tool.execute(
                Map.of("prompt", "Продолжи", "profile", "code"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("code", 2)).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        assertEquals(3, executor.config.getDelegationDepth());
        assertFalse(executor.config.isToolAllowed("task")); //$NON-NLS-1$
        assertFalse(executor.config.isToolAllowed("delegate_to_agent")); //$NON-NLS-1$
    }

    @Test
    public void builderFromPreservesDelegationDepth() {
        AgentConfig original = AgentConfig.builder().delegationDepth(2).build();

        AgentConfig copy = AgentConfig.builder().from(original).build();

        assertEquals(2, copy.getDelegationDepth());
    }
}
