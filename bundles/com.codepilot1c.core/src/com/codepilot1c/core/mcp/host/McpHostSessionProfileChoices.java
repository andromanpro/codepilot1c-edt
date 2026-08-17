/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Profile choices for the MCP host preference page.
 *
 * @param choices available choices, starting with the legacy unset choice
 * @param selectedIndex index of the configured profile choice
 */
public record McpHostSessionProfileChoices(List<Choice> choices, int selectedIndex) {

    /** Profile choice kind. */
    public enum Kind {
        UNSET,
        REGISTERED,
        UNKNOWN
    }

    /**
     * A profile identifier and how it relates to the current registry.
     *
     * @param id profile identifier
     * @param kind choice kind
     */
    public record Choice(String id, Kind kind) {
        public Choice {
            Objects.requireNonNull(id, "id"); //$NON-NLS-1$
            Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
        }
    }

    public McpHostSessionProfileChoices {
        choices = List.copyOf(choices);
        if (choices.isEmpty() || choices.get(0).kind() != Kind.UNSET
                || !choices.get(0).id().isEmpty()) {
            throw new IllegalArgumentException("First choice must be unset"); //$NON-NLS-1$
        }
        if (selectedIndex < 0 || selectedIndex >= choices.size()) {
            throw new IllegalArgumentException("Selected index is out of range"); //$NON-NLS-1$
        }
    }

    /**
     * Builds choices without losing a configured profile missing from the registry.
     *
     * @param configuredId configured profile identifier
     * @param registeredIds registered profile identifiers in display order
     * @return immutable choices and selected index
     */
    public static McpHostSessionProfileChoices of(
            String configuredId, List<String> registeredIds) {
        String normalizedId = configuredId == null ? "" : configuredId.trim(); //$NON-NLS-1$
        List<Choice> result = new ArrayList<>();
        result.add(new Choice("", Kind.UNSET)); //$NON-NLS-1$
        int selected = 0;
        for (String registeredId : registeredIds) {
            String id = Objects.requireNonNull(registeredId, "registeredId"); //$NON-NLS-1$
            result.add(new Choice(id, Kind.REGISTERED));
            if (id.equals(normalizedId)) {
                selected = result.size() - 1;
            }
        }
        if (!normalizedId.isEmpty() && selected == 0) {
            result.add(new Choice(normalizedId, Kind.UNKNOWN));
            selected = result.size() - 1;
        }
        return new McpHostSessionProfileChoices(result, selected);
    }

    /**
     * Returns the identifier selected for persistence.
     *
     * @return selected profile identifier, or an empty string for legacy mode
     */
    public String selectedId() {
        return choices.get(selectedIndex).id();
    }
}
