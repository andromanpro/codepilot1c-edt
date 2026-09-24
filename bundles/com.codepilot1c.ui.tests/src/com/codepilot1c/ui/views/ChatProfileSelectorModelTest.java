/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.gsd.GsdFeatureGate;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.ui.ChatToolGate;

public class ChatProfileSelectorModelTest {

    private String previousOverride;

    @Before
    public void rememberGsdOverride() {
        previousOverride = System.getProperty(GsdFeatureGate.JVM_PROPERTY);
    }

    @After
    public void restoreGsdOverride() {
        if (previousOverride == null) {
            System.clearProperty(GsdFeatureGate.JVM_PROPERTY);
        } else {
            System.setProperty(GsdFeatureGate.JVM_PROPERTY, previousOverride);
        }
    }

    @Test
    public void regularProfilesStayVisibleWithGsdDisabled() {
        System.setProperty(GsdFeatureGate.JVM_PROPERTY, Boolean.FALSE.toString());

        List<String> ids = optionIds();

        assertTrue(ids.containsAll(List.of("build", "plan", "explore"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(ids.stream().anyMatch(GsdFeatureGate::isGsdProfile));
    }

    @Test
    public void gsdProfilesAreAddedWithoutReplacingRegularProfiles() {
        System.setProperty(GsdFeatureGate.JVM_PROPERTY, Boolean.TRUE.toString());

        List<String> ids = optionIds();

        assertTrue(ids.containsAll(List.of("build", "plan", "explore"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ids.stream().anyMatch(GsdFeatureGate::isGsdProfile));
    }

    @Test
    public void exploreCanBeSelectedInCleanDialogBeforeFirstTurn() {
        Session cleanDialog = new Session("clean-dialog"); //$NON-NLS-1$

        assertTrue(ChatProfileSelectorModel.select(cleanDialog, "explore")); //$NON-NLS-1$

        assertEquals("explore", cleanDialog.getAgentProfile()); //$NON-NLS-1$
        assertEquals("explore", ChatToolGate.selectProfile(cleanDialog.getAgentProfile()).getId()); //$NON-NLS-1$
        assertTrue(ChatToolGate.selectProfile(cleanDialog.getAgentProfile()).isReadOnly());
    }

    @Test
    public void newDialogInheritsSharedExploreSelectionInsteadOfBuild() {
        Session newDialog = new Session("new-dialog"); //$NON-NLS-1$

        assertEquals("explore", ChatProfileSelectorModel.synchronize(newDialog, "explore")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("explore", newDialog.getAgentProfile()); //$NON-NLS-1$
        assertEquals("explore", ChatToolGate.selectProfile(newDialog.getAgentProfile()).getId()); //$NON-NLS-1$
    }

    @Test
    public void newChatSessionRetainsCurrentPerViewSelection() {
        Session previous = new Session("previous"); //$NON-NLS-1$
        Session next = new Session("next"); //$NON-NLS-1$
        assertTrue(ChatProfileSelectorModel.select(previous, "plan")); //$NON-NLS-1$

        assertEquals("plan", ChatProfileSelectorModel.carrySelection(previous, next, "build")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("plan", next.getAgentProfile()); //$NON-NLS-1$
    }

    @Test
    public void disabledSelectedGsdProfileFallsBackToExploreAndRefreshesOptions() {
        Session session = new Session("gsd-dialog"); //$NON-NLS-1$
        System.setProperty(GsdFeatureGate.JVM_PROPERTY, Boolean.TRUE.toString());
        assertTrue(ChatProfileSelectorModel.select(session, "gsd-discuss")); //$NON-NLS-1$

        System.setProperty(GsdFeatureGate.JVM_PROPERTY, Boolean.FALSE.toString());

        assertEquals("explore", ChatProfileSelectorModel.synchronize(session, "gsd-discuss")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("explore", session.getAgentProfile()); //$NON-NLS-1$
        assertTrue(optionIds().containsAll(List.of("build", "plan", "explore"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(optionIds().stream().anyMatch(GsdFeatureGate::isGsdProfile));
    }

    @Test
    public void selectorLabelsExposeCanonicalProfileNamesAndReadOnlyState() {
        List<String> labels = ChatProfileSelectorModel.availableOptions().stream()
                .map(ChatProfileSelectorModel.Option::label)
                .toList();

        assertTrue(labels.stream().anyMatch(label -> label.startsWith("Build "))); //$NON-NLS-1$
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("Plan ") //$NON-NLS-1$
                && label.contains("read-only"))); //$NON-NLS-1$
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("Explore ") //$NON-NLS-1$
                && label.contains("read-only"))); //$NON-NLS-1$
    }

    private List<String> optionIds() {
        return ChatProfileSelectorModel.availableOptions().stream()
                .map(ChatProfileSelectorModel.Option::id)
                .toList();
    }
}
