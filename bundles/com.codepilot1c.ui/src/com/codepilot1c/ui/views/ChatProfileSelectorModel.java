/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.ui.ChatToolGate;

/** Testable state model for the native ChatView profile selector. */
public final class ChatProfileSelectorModel {

    public record Option(String id, String label, String description) {
    }

    private ChatProfileSelectorModel() {
    }

    public static List<Option> availableOptions() {
        return AgentProfileRegistry.getInstance().getAvailableProfiles().stream()
                .map(profile -> new Option(
                        profile.getId(),
                        displayLabel(profile),
                        profile.getDescription()))
                .toList();
    }

    /**
     * Resolves and stores the profile that the next turn will actually use.
     * A per-view session choice wins over the shared default.
     */
    public static String synchronize(Session session, String configuredProfileId) {
        String sessionProfileId = session != null ? session.getAgentProfile() : null;
        String requestedProfileId = sessionProfileId != null && !sessionProfileId.isBlank()
                ? sessionProfileId : configuredProfileId;
        String effectiveProfileId = ChatToolGate.selectProfile(requestedProfileId).getId();
        if (session != null) {
            select(session, effectiveProfileId);
        }
        return effectiveProfileId;
    }

    /** Selects only a currently available profile. */
    public static boolean select(Session session, String profileId) {
        if (session == null || profileId == null || profileId.isBlank()) {
            return false;
        }
        return AgentProfileRegistry.getInstance().getAvailableProfile(profileId)
                .map(profile -> {
                    session.setAgentProfile(profile.getId());
                    return true;
                })
                .orElse(false);
    }

    /** Carries the effective choice across ChatView's new-dialog session boundary. */
    public static String carrySelection(Session previous, Session next, String configuredProfileId) {
        String profileId = synchronize(previous, configuredProfileId);
        select(next, profileId);
        return profileId;
    }

    private static String displayLabel(AgentProfile profile) {
        String idLabel = Arrays.stream(profile.getId().split("-")) //$NON-NLS-1$
                .map(ChatProfileSelectorModel::capitalize)
                .reduce((left, right) -> left + " " + right) //$NON-NLS-1$
                .orElse(profile.getId());
        String accessLabel = profile.isReadOnly() ? " · read-only" : ""; //$NON-NLS-1$ //$NON-NLS-2$
        return idLabel + " — " + profile.getName() + accessLabel; //$NON-NLS-1$
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        if ("gsd".equals(value)) { //$NON-NLS-1$
            return "GSD"; //$NON-NLS-1$
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
