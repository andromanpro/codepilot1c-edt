package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.profiles.DelegationClamp;
import com.codepilot1c.core.agent.profiles.ExploreAgentProfile;
import com.codepilot1c.core.agent.profiles.ProfileRouter;

public class TaskToolDelegationClampTest extends DelegationToolTestSupport {

    @Test
    public void readOnlyParentCannotSpawnMutatingProfile() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        TaskTool tool = taskTool(executor);

        ToolResult result = tool.execute(
                Map.of("prompt", "Измени код", "profile", "code"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("plan", 0)).join(); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertNull(executor.config);
        assertEquals("delegation_denied", result.getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("task", result.getStructuredString("tool")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DelegationClamp.REASON_CAPABILITY_EXCEEDED,
                result.getStructuredString("reason")); //$NON-NLS-1$
        assertEquals(DelegationClamp.REASON_CAPABILITY_EXCEEDED,
                result.getStructuredString("reason_code")); //$NON-NLS-1$
        assertEquals("plan", result.getStructuredString("parent_profile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("code", result.getStructuredString("requested_profile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("code", result.getStructuredString("resolved_profile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("READ_ONLY", result.getStructuredString("parent_ceiling")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("MUTATING", result.getStructuredString("required_capability")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void readOnlyParentAutoIsClampedToReadOnlyProfile() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        TaskTool tool = taskTool(executor);

        ToolResult result = tool.execute(
                Map.of("prompt", "Создай справочник Товары", "profile", "auto"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("plan", 0)).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        assertEquals(ExploreAgentProfile.ID, executor.config.getProfileName());
        assertEquals(1, executor.config.getDelegationDepth());
        assertEquals("metadata", result.getStructuredString("clamped_from")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("explore", result.getStructuredString("clamped_to")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DelegationClamp.REASON_AUTO_CLAMPED,
                result.getStructuredString("reason_code")); //$NON-NLS-1$
        assertTrue(result.getContent().contains("Профиль ограничен политикой родителя")); //$NON-NLS-1$
    }

    @Test
    public void clampedAgentFailureKeepsClampMetadata() throws Exception {
        TaskTool tool = taskTool((provider, registry, profile, prompt, config) ->
                AgentResult.error(
                        new IllegalStateException("agent failed"), //$NON-NLS-1$
                        Collections.emptyList(), 2, 7));

        ToolResult result = tool.execute(
                Map.of("prompt", "Создай справочник Товары", "profile", "auto"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("plan", 0)).join(); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertEquals("ERROR", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("metadata", result.getStructuredString("clamped_from")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("explore", result.getStructuredString("clamped_to")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DelegationClamp.REASON_AUTO_CLAMPED,
                result.getStructuredString("reason_code")); //$NON-NLS-1$
    }

    @Test
    public void clampedExecutorExceptionKeepsClampMetadata() throws Exception {
        TaskTool tool = taskTool((provider, registry, profile, prompt, config) -> {
            throw new IllegalStateException("executor failed"); //$NON-NLS-1$
        });

        ToolResult result = tool.execute(
                Map.of("prompt", "Создай справочник Товары", "profile", "auto"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("plan", 0)).join(); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertEquals("IllegalStateException", result.getStructuredString("error_type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("metadata", result.getStructuredString("clamped_from")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("explore", result.getStructuredString("clamped_to")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DelegationClamp.REASON_AUTO_CLAMPED,
                result.getStructuredString("reason_code")); //$NON-NLS-1$
    }

    @Test
    public void orchestratorParentCanSpawnMutatingProfile() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();

        ToolResult result = taskTool(executor).execute(
                Map.of("prompt", "Измени код", "profile", "code"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("orchestrator", 1)).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        assertEquals("code", executor.config.getProfileName()); //$NON-NLS-1$
        assertEquals(2, executor.config.getDelegationDepth());
    }

    @Test
    public void buildParentKeepsFullDelegation() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();

        ToolResult result = taskTool(executor).execute(
                Map.of("prompt", "Создай справочник", "profile", "metadata"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("build", 0)).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        assertEquals("metadata", executor.config.getProfileName()); //$NON-NLS-1$
    }

    @Test
    public void unscopedContextKeepsLegacyBehaviour() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();

        ToolResult result = taskTool(executor).execute(
                Map.of("prompt", "Создай справочник", "profile", "metadata")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .join();

        assertTrue(result.isSuccess());
        assertEquals("metadata", executor.config.getProfileName()); //$NON-NLS-1$
        assertEquals(1, executor.config.getDelegationDepth());
    }

    @Test
    public void modelSuppliedCapabilityArgumentsAreIgnored() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("prompt", "Измени код"); //$NON-NLS-1$ //$NON-NLS-2$
        parameters.put("profile", "code"); //$NON-NLS-1$ //$NON-NLS-2$
        parameters.put("parent_profile", "build"); //$NON-NLS-1$ //$NON-NLS-2$
        parameters.put("__parent_capability", "MUTATING"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = taskTool(executor).execute(parameters, context("plan", 0)).join(); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertNull(executor.config);
        assertEquals("plan", result.getStructuredString("parent_profile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("READ_ONLY", result.getStructuredString("parent_ceiling")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void scopedUnknownTargetHasDeterministicFailure() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();

        ToolResult result = taskTool(executor).execute(
                Map.of("prompt", "Проверь", "profile", "missing-profile"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                context("build", 0)).join(); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertNull(executor.config);
        assertEquals(DelegationClamp.REASON_TARGET_UNRESOLVED,
                result.getStructuredString("reason_code")); //$NON-NLS-1$
    }

    @Test
    public void denialPayloadIsDeterministic() throws Exception {
        TaskTool tool = taskTool(new CapturingExecutor());
        Map<String, Object> parameters =
                Map.of("prompt", "Измени код", "profile", "code"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        ToolResult first = tool.execute(parameters, context("plan", 0)).join(); //$NON-NLS-1$
        ToolResult second = tool.execute(parameters, context("plan", 0)).join(); //$NON-NLS-1$

        assertEquals(first.getErrorMessage(), second.getErrorMessage());
        assertEquals(first.getStructuredData(), second.getStructuredData());
    }

    private TaskTool taskTool(TaskTool.SubagentExecutor executor) throws Exception {
        return new TaskTool(placeholderRegistry(), new ProfileRouter(), executor);
    }
}
