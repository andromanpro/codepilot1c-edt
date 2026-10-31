package com.codepilot1c.core.mcp.host;

import com.codepilot1c.core.mcp.host.McpHostConfig.MutationPolicy;

/**
 * Decides whether a tool that asks for confirmation must be refused over the MCP
 * host transport, which has no channel to ask a human.
 *
 * <p>Separate class on purpose: the rule is the whole subject of the defect and
 * has to be testable without constructing the router, which drags in the tool
 * registry, the exposure policy and an Eclipse workbench.</p>
 *
 * <p><b>Why this exists.</b> Upstream 1.3.9 refuses every confirmation-requiring
 * tool on this transport unconditionally. That is right when the session profile
 * gate is on — there the profile's own ASK branch is the confirmation mechanism.
 * With no session profile the host runs in legacy mode, the profile block is
 * skipped entirely, and the unconditional refusal then contradicts the operator
 * decision already recorded in {@link MutationPolicy}: the host answers DENY to
 * a tool the configuration says to ALLOW. Practical effect measured 2026-08-28:
 * the whole mutating surface died, and with it {@code edt_diagnostics
 * uuid_check}, which mutates nothing — the classification is per tool, and
 * {@code edt_diagnostics} carries {@code update_infobase} among its commands.</p>
 *
 * <p>So the rule honours the configured policy instead of overriding it, and
 * only in legacy mode. ASK keeps refusing: asking is exactly what this transport
 * cannot do.</p>
 */
public final class McpHostConfirmationPolicy {

    private McpHostConfirmationPolicy() {
    }

    /**
     * @param requiresConfirmation as classified for the resolved tool
     * @param profileGateEnabled whether a session profile is configured
     * @param mutationPolicy operator setting for mutations over this transport
     * @return {@code true} when the call must be refused as unconfirmable
     */
    public static boolean refusesUnconfirmable(boolean requiresConfirmation,
            boolean profileGateEnabled, MutationPolicy mutationPolicy) {
        if (!requiresConfirmation) {
            return false;
        }
        if (profileGateEnabled) {
            // The profile gate owns the decision and has already run; keep the
            // upstream contract untouched there.
            return true;
        }
        // Legacy mode: no profile, so the mutation policy is the only recorded
        // operator intent. ALLOW means "run mutations here"; anything else, and
        // absence of a policy, stays refused.
        return mutationPolicy != MutationPolicy.ALLOW;
    }
}
