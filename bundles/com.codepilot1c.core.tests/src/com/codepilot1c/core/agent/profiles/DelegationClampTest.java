package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.DelegationClamp.Decision;
import com.codepilot1c.core.agent.profiles.DelegationClamp.Outcome;

public class DelegationClampTest {

    private final AgentProfileRegistry registry = AgentProfileRegistry.getInstance();
    private final ProfileRouter router = new ProfileRouter();

    @Test
    public void everyReadOnlyNonBrokerRejectsEveryMutatingDelegateTarget() {
        int checkedParents = 0;
        for (AgentProfile parent : registry.getAllProfiles()) {
            if (ProfileCapabilities.executionCapability(parent) != AgentCapability.READ_ONLY
                    || ProfileCapabilities.delegationCeiling(parent) != AgentCapability.READ_ONLY) {
                continue;
            }
            checkedParents++;
            for (String targetId : router.supportedDelegateTargets()) {
                if ("auto".equals(targetId)) { //$NON-NLS-1$
                    continue;
                }
                AgentProfile target = profile(targetId);
                if (ProfileCapabilities.requiredForChild(target) == AgentCapability.MUTATING) {
                    Decision decision = decide(parent, false, targetId, target);
                    assertEquals(parent.getId() + " -> " + targetId, //$NON-NLS-1$
                            Outcome.DENIED, decision.outcome());
                    assertEquals(DelegationClamp.REASON_CAPABILITY_EXCEEDED, decision.reasonCode());
                }
            }
        }
        assertTrue("Registry must contain read-only non-broker profiles", checkedParents > 0); //$NON-NLS-1$
    }

    @Test
    public void readOnlyProfilesMayDelegateToReadOnlyProfiles() {
        assertEquals(Outcome.ALLOWED, decide(profile("plan"), false, "explore", profile("explore")).outcome()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Outcome.ALLOWED, decide(profile("explore"), false, "plan", profile("plan")).outcome()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Outcome.ALLOWED, decide(profile("plan"), false, "plan", profile("plan")).outcome()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void readOnlyParentCannotLaunderThroughOrchestrator() {
        Decision decision = decide(
                profile("plan"), false, "orchestrator", profile("orchestrator")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(Outcome.DENIED, decision.outcome());
        assertEquals(AgentCapability.MUTATING, decision.requiredCapability());
    }

    @Test
    public void orchestratorMayDelegateToEveryCurrentBuildProfile() {
        AgentProfile orchestrator = profile("orchestrator"); //$NON-NLS-1$
        for (String targetId : List.of(
                "build", "init", "code", "metadata", "qa", "dcs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "extension", "recovery")) { //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(targetId, Outcome.ALLOWED,
                    decide(orchestrator, false, targetId, profile(targetId)).outcome());
        }
    }

    @Test
    public void everyMutatingProfileMayDelegateToEverySupportedExplicitTarget() {
        for (AgentProfile parent : registry.getAllProfiles()) {
            if (ProfileCapabilities.executionCapability(parent) != AgentCapability.MUTATING) {
                continue;
            }
            for (String targetId : router.supportedDelegateTargets()) {
                if ("auto".equals(targetId)) { //$NON-NLS-1$
                    continue;
                }
                assertEquals(parent.getId() + " -> " + targetId, Outcome.ALLOWED, //$NON-NLS-1$
                        decide(parent, false, targetId, profile(targetId)).outcome());
            }
        }
    }

    @Test
    public void autoMutationIsClampedToExploreUnderReadOnlyCeiling() {
        assertAutoClamped("Создай справочник Товары"); //$NON-NLS-1$
        assertAutoClamped("Создай справочник Товары и напиши QA сценарий"); //$NON-NLS-1$
    }

    @Test
    public void decisionIsDeterministic() {
        AgentProfile parent = profile("plan"); //$NON-NLS-1$
        AgentProfile resolved = profile(router.route("Создай справочник Товары")); //$NON-NLS-1$
        Decision first = decide(parent, true, "auto", resolved); //$NON-NLS-1$

        for (int i = 0; i < 100; i++) {
            assertEquals(first, decide(parent, true, "auto", resolved)); //$NON-NLS-1$
        }
    }

    private void assertAutoClamped(String prompt) {
        AgentProfile resolved = profile(router.route(prompt));
        Decision decision = decide(profile("plan"), true, "auto", resolved); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Outcome.CLAMPED, decision.outcome());
        assertEquals(ExploreAgentProfile.ID, decision.effectiveProfileId());
        assertEquals(DelegationClamp.REASON_AUTO_CLAMPED, decision.reasonCode());
    }

    private Decision decide(
            AgentProfile parent, boolean autoRequested, String requested, AgentProfile target) {
        return DelegationClamp.decide(
                ProfileCapabilities.delegationCeiling(parent),
                autoRequested,
                requested,
                target,
                ignored -> ExploreAgentProfile.ID);
    }

    private AgentProfile profile(String id) {
        return registry.getProfile(id).orElseThrow(() -> new AssertionError("Missing profile: " + id)); //$NON-NLS-1$
    }
}
