/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpHostSessionProfileChoices.Choice;
import com.codepilot1c.core.mcp.host.McpHostSessionProfileChoices.Kind;

public class McpHostSessionProfileChoicesTest {

    @Test
    public void unknownConfiguredIdBecomesItsOwnMarkedChoice() throws IOException {
        McpHostSessionProfileChoices result = McpHostSessionProfileChoices.of(
                "no-such-profile", List.of("build", "explore")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(new Choice("no-such-profile", Kind.UNKNOWN), //$NON-NLS-1$
                result.choices().get(result.selectedIndex()));
        assertTrue(result.selectedIndex() > 0);

        String pageSource = Files.readString(repositoryRoot().resolve(
                "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/McpHostPreferencePage.java"), //$NON-NLS-1$
                StandardCharsets.UTF_8);
        assertFalse(pageSource.contains("selectedProfile >= 0 ? selectedProfile : 0")); //$NON-NLS-1$
        assertTrue(pageSource.contains("McpHostSessionProfileChoices")); //$NON-NLS-1$
    }

    @Test
    public void configuredIdSurvivesRoundTripForEveryInputClass() {
        List<String> registered = List.of("build", "explore"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("", McpHostSessionProfileChoices.of(null, registered).selectedId()); //$NON-NLS-1$
        assertEquals("", McpHostSessionProfileChoices.of("", registered).selectedId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", McpHostSessionProfileChoices.of("  ", registered).selectedId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("build", McpHostSessionProfileChoices.of("build", registered).selectedId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("no-such-profile", //$NON-NLS-1$
                McpHostSessionProfileChoices.of("no-such-profile", registered).selectedId()); //$NON-NLS-1$
    }

    @Test
    public void blankConfiguredIdSelectsUnsetAtIndexZero() {
        McpHostSessionProfileChoices result = McpHostSessionProfileChoices.of(
                "", List.of("build")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(0, result.selectedIndex());
        assertEquals(new Choice("", Kind.UNSET), result.choices().get(0)); //$NON-NLS-1$
    }

    @Test
    public void registeredProfileIsNotDuplicatedAsUnknown() {
        McpHostSessionProfileChoices result = McpHostSessionProfileChoices.of(
                "build", List.of("build", "explore")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(1, result.selectedIndex());
        assertEquals(1, result.choices().stream()
                .filter(choice -> "build".equals(choice.id())) //$NON-NLS-1$
                .count());
        assertFalse(result.choices().stream()
                .anyMatch(choice -> choice.kind() == Kind.UNKNOWN));
    }

    @Test
    public void emptyRegistryStillPreservesUnknownConfiguredId() {
        McpHostSessionProfileChoices result = McpHostSessionProfileChoices.of(
                "orphan", List.of()); //$NON-NLS-1$

        assertEquals("orphan", result.selectedId()); //$NON-NLS-1$
        assertEquals(new Choice("orphan", Kind.UNKNOWN), //$NON-NLS-1$
                result.choices().get(result.selectedIndex()));
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory"); //$NON-NLS-1$
        Path current = configured != null && !configured.isBlank()
                ? Path.of(configured).toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve(
                            "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/McpHostPreferencePage.java"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
