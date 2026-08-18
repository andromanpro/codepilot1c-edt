/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.permissions.ProfilePermissionGate.GateDecision;
import com.codepilot1c.core.permissions.ProfilePermissionGate.GateResult;

public class ProfilePermissionGateTest {

    @Test
    public void profileDenyWinsOverGlobalAllow() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(PermissionRule.deny("write_file") //$NON-NLS-1$
                        .forResourcePattern("**/*.mdo").build()), //$NON-NLS-1$
                List.of(PermissionRule.allow("write_file").forAllResources()), //$NON-NLS-1$
                "write_file", Map.of("path", "src/Configuration/Configuration.mdo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.DENY, result.decision());
        assertEquals("profile", result.layer()); //$NON-NLS-1$
    }

    @Test
    public void globalDenyWinsOverProfileAllow() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(PermissionRule.allow("write_file").forAllResources()), //$NON-NLS-1$
                List.of(PermissionRule.deny("write_file").forAllResources()), //$NON-NLS-1$
                "write_file", Map.of("path", "file.txt")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.DENY, result.decision());
        assertEquals("global", result.layer()); //$NON-NLS-1$
    }

    @Test
    public void noRulesGiveNoRule() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(), List.of(), "read_file", Map.of()); //$NON-NLS-1$

        assertEquals(GateDecision.NO_RULE, result.decision());
        assertEquals("none", result.layer()); //$NON-NLS-1$
    }

    @Test
    public void askIsNotLoosenedByAllow() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(PermissionRule.ask("write_file").forAllResources()), //$NON-NLS-1$
                List.of(PermissionRule.allow("write_file").forAllResources()), //$NON-NLS-1$
                "write_file", Map.of("path", "file.txt")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.ASK, result.decision());
        assertEquals("profile", result.layer()); //$NON-NLS-1$
    }

    @Test
    public void allowWhenOtherLayerAbstains() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(PermissionRule.allow("read_file").forAllResources()), //$NON-NLS-1$
                List.of(), "read_file", Map.of("path", "file.txt")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.ALLOW, result.decision());
        assertEquals("profile", result.layer()); //$NON-NLS-1$
    }

    @Test
    public void profileDenyIsNotMaskedByHigherPriorityAskInSameLayer() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(
                        PermissionRule.ask("write_file").withPriority(100).forAllResources(), //$NON-NLS-1$
                        PermissionRule.deny("write_file").withPriority(1) //$NON-NLS-1$
                                .forResourcePattern("**/*.mdo").build()), //$NON-NLS-1$
                List.of(), "write_file", //$NON-NLS-1$
                Map.of("path", "src/Configuration/Configuration.mdo")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(GateDecision.DENY, result.decision());
        assertEquals("profile", result.layer()); //$NON-NLS-1$
    }

    @Test
    public void globalDenyIsNotMaskedByHigherPriorityAllowInSameLayer() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(),
                List.of(
                        PermissionRule.allow("write_file").withPriority(100).forAllResources(), //$NON-NLS-1$
                        PermissionRule.deny("write_file").withPriority(1) //$NON-NLS-1$
                                .forResourcePattern("**/*.mdo").build()), //$NON-NLS-1$
                "write_file", Map.of("path", "src/Configuration/Configuration.mdo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.DENY, result.decision());
        assertEquals("global", result.layer()); //$NON-NLS-1$
    }

    @Test
    public void mdoRuleMatchesNormalizedPathVariants() {
        List<PermissionRule> rules = List.of(PermissionRule.deny("write_file") //$NON-NLS-1$
                .forResourcePattern("**/*.mdo").build()); //$NON-NLS-1$

        for (String path : List.of(
                "Configuration.mdo", //$NON-NLS-1$
                "src\\Configuration\\Configuration.mdo", //$NON-NLS-1$
                "src/Configuration/Configuration.MDO")) { //$NON-NLS-1$
            GateResult result = ProfilePermissionGate.evaluate(
                    rules, List.of(), "write_file", Map.of("path", path)); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(path, GateDecision.DENY, result.decision());
        }
    }

    @Test
    public void strictGateNormalizesGenericArtifactExtensions() {
        for (String extension : List.of("form", "mxl")) { //$NON-NLS-1$ //$NON-NLS-2$
            List<PermissionRule> rules = List.of(PermissionRule.deny("write_file") //$NON-NLS-1$
                    .forResourcePattern("**/*." + extension).build()); //$NON-NLS-1$

            GateResult result = ProfilePermissionGate.evaluate(
                    rules, List.of(), "write_file", //$NON-NLS-1$
                    Map.of("path", "Artifact." + extension.toUpperCase())); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals(extension, GateDecision.DENY, result.decision());
        }
    }
}
