/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class PermissionEvaluatorTest {

    @Test
    public void resourceOfPrefersPathKey() {
        assertEquals("a.bsl", PermissionEvaluator.resourceOf(Map.of( //$NON-NLS-1$
                "command", "echo test", //$NON-NLS-1$ //$NON-NLS-2$
                "path", "a.bsl"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void resourceOfFallsBackToAnyResource() {
        assertEquals(PermissionEvaluator.ANY_RESOURCE, PermissionEvaluator.resourceOf(null));
        assertEquals(PermissionEvaluator.ANY_RESOURCE, PermissionEvaluator.resourceOf(Map.of()));
    }

    @Test
    public void resourceOfKeepsLegacyRawPathSeparators() {
        assertEquals("src\\Configuration\\Configuration.MDO", //$NON-NLS-1$
                PermissionEvaluator.resourceOf(Map.of(
                        "path", "src\\Configuration\\Configuration.MDO"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void legacyResourceOfIgnoresGateOnlyKeys() {
        assertEquals(PermissionEvaluator.ANY_RESOURCE,
                PermissionEvaluator.resourceOf(Map.of(
                        "target_fqn", "Catalog.X"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void legacyResourceOfIgnoresRepoPath() {
        assertEquals(PermissionEvaluator.ANY_RESOURCE,
                PermissionEvaluator.resourceOf(Map.of("repo_path", "/x"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void gateResourceOfKeepsLegacyPrecedence() {
        assertEquals("p", PermissionEvaluator.gateResourceOf(Map.of( //$NON-NLS-1$
                "path", "p", //$NON-NLS-1$ //$NON-NLS-2$
                "target_fqn", "t"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void gateOnlyKeyOrderIsPinned() {
        List<String> keys = List.of(
                "target_fqn", "parent_fqn", "form_fqn", "owner_fqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "object_fqn", "template_fqn", "role", "repo_path"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String key : keys) {
            arguments.put(key, "value-" + key); //$NON-NLS-1$
        }

        for (String expectedKey : keys) {
            assertEquals("value-" + expectedKey, //$NON-NLS-1$
                    PermissionEvaluator.gateResourceOf(arguments));
            arguments.remove(expectedKey);
        }
    }

    @Test
    public void firstMatchReturnsHighestPriority() {
        PermissionRule low = PermissionRule.ask("write_file") //$NON-NLS-1$
                .withPriority(1)
                .forAllResources();
        PermissionRule high = PermissionRule.deny("write_file") //$NON-NLS-1$
                .withPriority(100)
                .forAllResources();

        assertEquals(high, PermissionEvaluator.firstMatch(
                List.of(low, high), "write_file", "file.txt").orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void firstMatchIsStableForEqualPriority() {
        PermissionRule first = PermissionRule.deny("write_file").forAllResources(); //$NON-NLS-1$
        PermissionRule second = PermissionRule.ask("write_file").forAllResources(); //$NON-NLS-1$

        assertEquals(first, PermissionEvaluator.firstMatch(
                List.of(first, second), "write_file", "file.txt").orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void firstMatchEmptyWhenNoRuleMatches() {
        assertFalse(PermissionEvaluator.firstMatch(
                List.of(PermissionRule.allow("read_file").forAllResources()), //$NON-NLS-1$
                "write_file", "file.txt").isPresent()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void firstMatchKeepsLegacyPriorityBeforeStrictness() {
        PermissionRule highAsk = PermissionRule.ask("write_file") //$NON-NLS-1$
                .withPriority(100)
                .forAllResources();
        PermissionRule lowDeny = PermissionRule.deny("write_file") //$NON-NLS-1$
                .withPriority(1)
                .forAllResources();

        assertEquals(highAsk, PermissionEvaluator.firstMatch(
                List.of(lowDeny, highAsk), "write_file", "file.txt").orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void firstMatchKeepsLegacyRawMatchingForBareReadme() {
        PermissionRule fallbackAsk = PermissionRule.ask("write_file") //$NON-NLS-1$
                .withPriority(10)
                .forAllResources();
        PermissionRule customAllow = PermissionRule.allow("write_file") //$NON-NLS-1$
                .withPriority(100)
                .forResourcePattern("**/*.md") //$NON-NLS-1$
                .build();

        assertEquals(fallbackAsk, PermissionEvaluator.firstMatch(
                List.of(fallbackAsk, customAllow), "write_file", "README.md").orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void firstMatchKeepsLegacyRawMatchingForWindowsResource() {
        PermissionRule fallbackAsk = PermissionRule.ask("write_file") //$NON-NLS-1$
                .withPriority(10)
                .forAllResources();
        PermissionRule customAllow = PermissionRule.allow("write_file") //$NON-NLS-1$
                .withPriority(100)
                .forResourcePattern("src/Cfg/*.mdo") //$NON-NLS-1$
                .build();

        assertEquals(fallbackAsk, PermissionEvaluator.firstMatch(
                List.of(fallbackAsk, customAllow), "write_file", //$NON-NLS-1$
                "src\\Cfg\\Configuration.mdo").orElseThrow()); //$NON-NLS-1$
    }

    @Test
    public void strictestMatchDoesNotAllowPriorityToMaskDeny() {
        PermissionRule highAsk = PermissionRule.ask("write_file") //$NON-NLS-1$
                .withPriority(100)
                .forAllResources();
        PermissionRule lowDeny = PermissionRule.deny("write_file") //$NON-NLS-1$
                .withPriority(1)
                .forAllResources();

        assertEquals(lowDeny, PermissionEvaluator.strictestMatch(
                List.of(highAsk, lowDeny), "write_file", "file.txt").orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
