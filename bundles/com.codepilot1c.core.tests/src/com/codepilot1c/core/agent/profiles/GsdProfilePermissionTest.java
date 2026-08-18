package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.permissions.PermissionDecision;
import com.codepilot1c.core.permissions.PermissionEvaluator;
import com.codepilot1c.core.permissions.PermissionRule;

/**
 * Tests for GSD phase-profile permission rules.
 *
 * <p>Verifies that EDT-protected profiles (Execute, Ship) deny
 * write_file and edit_file for {@code **&#47;*.mdo} before general ask rules,
 * mirroring the protection already enforced by the Execute profile.</p>
 */
public class GsdProfilePermissionTest {

    // ---- GsdShipProfile: deny *.mdo before ask -------------------------

    @Test
    public void shipProfileDeniesWriteFileForMdo() {
        GsdShipProfile profile = new GsdShipProfile();
        PermissionDecision decision = evaluateFirstMatch(
                profile.getDefaultPermissions(), "write_file", "src/Configuration/Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file for *.mdo must be DENIED in Ship profile", //$NON-NLS-1$
                PermissionDecision.DENY, decision);
    }

    @Test
    public void shipProfileAsksWriteFileForNonMdo() {
        GsdShipProfile profile = new GsdShipProfile();
        PermissionDecision decision = evaluateFirstMatch(
                profile.getDefaultPermissions(), "write_file", "release-notes.md"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file for non-.mdo must be ASK in Ship profile", //$NON-NLS-1$
                PermissionDecision.ASK, decision);
    }

    @Test
    public void shipProfileDeniesEditFileForMdo() {
        GsdShipProfile profile = new GsdShipProfile();
        PermissionDecision decision = evaluateStrictestMatch(
                profile.getDefaultPermissions(), "edit_file", //$NON-NLS-1$
                "src/Configuration/Configuration.mdo"); //$NON-NLS-1$
        assertEquals(PermissionDecision.DENY, decision);
    }

    @Test
    public void shipProfileDenyRuleAppearsBeforeAskRule() {
        GsdShipProfile profile = new GsdShipProfile();
        List<PermissionRule> rules = profile.getDefaultPermissions();
        int denyIndex = -1;
        int askIndex = -1;
        for (int i = 0; i < rules.size(); i++) {
            PermissionRule r = rules.get(i);
            if ("write_file".equals(r.getToolName())) { //$NON-NLS-1$
                if (r.getDecision() == PermissionDecision.DENY) {
                    denyIndex = i;
                } else if (r.getDecision() == PermissionDecision.ASK) {
                    askIndex = i;
                }
            }
        }
        assertTrue("Ship profile must have a deny rule for write_file", denyIndex >= 0); //$NON-NLS-1$
        assertTrue("Ship profile must have an ask rule for write_file", askIndex >= 0); //$NON-NLS-1$
        assertTrue("deny rule must appear before ask rule (priority-equal = list order)", //$NON-NLS-1$
                denyIndex < askIndex);
    }

    // ---- GsdExecuteProfile: deny *.mdo before ask ----------------------

    @Test
    public void executeProfileDeniesWriteFileForMdo() {
        GsdExecuteProfile profile = new GsdExecuteProfile();
        PermissionDecision decision = evaluateFirstMatch(
                profile.getDefaultPermissions(), "write_file", "src/Configuration/Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file for *.mdo must be DENIED in Execute profile", //$NON-NLS-1$
                PermissionDecision.DENY, decision);
    }

    @Test
    public void executeProfileAsksWriteFileForNonMdo() {
        GsdExecuteProfile profile = new GsdExecuteProfile();
        PermissionDecision decision = evaluateFirstMatch(
                profile.getDefaultPermissions(), "write_file", "src/Main.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file for non-.mdo must be ASK in Execute profile", //$NON-NLS-1$
                PermissionDecision.ASK, decision);
    }

    @Test
    public void executeProfileDeniesEditFileForMdo() {
        GsdExecuteProfile profile = new GsdExecuteProfile();
        PermissionDecision decision = evaluateStrictestMatch(
                profile.getDefaultPermissions(), "edit_file", //$NON-NLS-1$
                "src/Configuration/Configuration.mdo"); //$NON-NLS-1$
        assertEquals(PermissionDecision.DENY, decision);
    }

    // ---- Helper --------------------------------------------------------

    /**
     * Evaluates rules against a tool/resource pair using the same logic as
     * {@link PermissionEvaluator#firstMatch}: filter matching rules, sort by
     * priority descending, return first match's decision.
     */
    private static PermissionDecision evaluateFirstMatch(
            List<PermissionRule> rules, String toolName, String resource) {
        return PermissionEvaluator.firstMatch(rules, toolName, resource)
                .map(PermissionRule::getDecision)
                .orElse(PermissionDecision.ASK);
    }

    private static PermissionDecision evaluateStrictestMatch(
            List<PermissionRule> rules, String toolName, String resource) {
        return PermissionEvaluator.strictestMatch(rules, toolName, resource)
                .map(PermissionRule::getDecision)
                .orElse(PermissionDecision.ASK);
    }
}
