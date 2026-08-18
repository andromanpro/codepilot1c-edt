package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

public class ProfileCapabilityContractTest {

    @Test
    public void everyProfileHasANonNullDelegationCeiling() {
        for (AgentProfile profile : AgentProfileRegistry.getInstance().getAllProfiles()) {
            assertNotNull(profile.getId(), profile.getDelegationCeiling());
            assertNotNull(profile.getId(), ProfileCapabilities.delegationCeiling(profile));
        }
    }

    @Test
    public void orchestratorIsTheOnlyDeclaredDelegationBroker() {
        Set<String> brokers = AgentProfileRegistry.getInstance().getAllProfiles().stream()
                .filter(profile -> ProfileCapabilities.delegationCeiling(profile)
                        != ProfileCapabilities.executionCapability(profile))
                .map(AgentProfile::getId)
                .collect(Collectors.toSet());

        assertTrue(brokers.contains(OrchestratorProfile.ID));
        assertTrue("Unexpected delegation brokers: " + brokers, //$NON-NLS-1$
                brokers.equals(Set.of(OrchestratorProfile.ID)));
    }

    @Test
    public void delegationCapableReadOnlyProfilesDoNotExposeDirectMutatingTools() {
        ToolRegistry tools = ToolRegistry.getInstance();
        for (AgentProfile profile : AgentProfileRegistry.getInstance().getAllProfiles()) {
            if (ProfileCapabilities.executionCapability(profile) != AgentCapability.READ_ONLY
                    || (!profile.getAllowedTools().contains("task") //$NON-NLS-1$
                            && !profile.getAllowedTools().contains("delegate_to_agent"))) { //$NON-NLS-1$
                continue;
            }
            for (String toolName : profile.getAllowedTools()) {
                ITool tool = tools.getTool(toolName);
                if (tool != null) {
                    assertFalse(profile.getId() + " exposes mutating tool " + toolName, tool.isMutating()); //$NON-NLS-1$
                }
            }
        }
    }
}
