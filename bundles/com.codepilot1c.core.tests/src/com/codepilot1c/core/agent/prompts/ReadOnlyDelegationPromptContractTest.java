package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class ReadOnlyDelegationPromptContractTest {

    private static final String ALLOWED_PROFILES = "auto|explore|plan"; //$NON-NLS-1$
    private static final Pattern TASK_PROFILE_INSTRUCTION =
            Pattern.compile("task\\(profile=([^)]*)\\)"); //$NON-NLS-1$

    @Test
    public void planPromptAdvertisesOnlyReadOnlyDelegationProfiles() {
        assertReadOnlyDelegationContract(AgentPromptTemplates.buildPlanPrompt());
    }

    @Test
    public void explorePromptAdvertisesOnlyReadOnlyDelegationProfiles() {
        assertReadOnlyDelegationContract(AgentPromptTemplates.buildExplorePrompt());
    }

    @Test
    public void backendAdapterRemovesCurrentReadOnlyDelegationInstructions() {
        assertDelegationInstructionRemoved(AgentPromptTemplates.buildPlanPrompt());
        assertDelegationInstructionRemoved(AgentPromptTemplates.buildExplorePrompt());
    }

    private void assertReadOnlyDelegationContract(String prompt) {
        Matcher matcher = TASK_PROFILE_INSTRUCTION.matcher(prompt);
        assertTrue("Read-only prompt must contain a task profile contract", matcher.find()); //$NON-NLS-1$
        assertEquals(ALLOWED_PROFILES, matcher.group(1));
        assertFalse("Read-only prompt must have one task profile contract", matcher.find()); //$NON-NLS-1$
        assertTrue(prompt.contains("явные mutating-профили запрещены read-only clamp")); //$NON-NLS-1$
        assertTrue(prompt.contains("auto, выбравший mutating-цель")); //$NON-NLS-1$
        assertTrue(prompt.contains("ограничен до explore")); //$NON-NLS-1$
    }

    private void assertDelegationInstructionRemoved(String prompt) {
        assertEquals(prompt, AgentPromptTemplates.adaptForBackend(prompt, true));
        String adapted = AgentPromptTemplates.adaptForBackend(prompt, false);
        assertFalse(adapted.contains("task(profile=")); //$NON-NLS-1$
        assertFalse(adapted.contains("read-only clamp")); //$NON-NLS-1$
    }
}
