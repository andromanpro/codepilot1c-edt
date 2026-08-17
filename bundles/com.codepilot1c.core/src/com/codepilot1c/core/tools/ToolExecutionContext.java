/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Objects;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.ProfileCapabilities;

/**
 * Immutable caller context carried explicitly across asynchronous tool calls.
 *
 * @param parentProfileId profile of the agent invoking the tool
 * @param delegationCeiling strongest child capability the parent may create
 * @param delegationDepth current delegation depth
 */
public record ToolExecutionContext(
        String parentProfileId,
        AgentCapability delegationCeiling,
        int delegationDepth) {

    private static final String UNSCOPED_PROFILE = ""; //$NON-NLS-1$
    private static final ToolExecutionContext UNSCOPED =
            new ToolExecutionContext(UNSCOPED_PROFILE, AgentCapability.MUTATING, 0);

    public ToolExecutionContext {
        Objects.requireNonNull(parentProfileId, "parentProfileId"); //$NON-NLS-1$
        Objects.requireNonNull(delegationCeiling, "delegationCeiling"); //$NON-NLS-1$
        if (delegationDepth < 0) {
            throw new IllegalArgumentException("delegationDepth must not be negative"); //$NON-NLS-1$
        }
    }

    /**
     * Compatibility context for legacy callers without a profile scope.
     *
     * @return fail-open unscoped context
     */
    public static ToolExecutionContext unscoped() {
        return UNSCOPED;
    }

    /**
     * Creates a context from the trusted parent profile.
     *
     * @param parentProfile parent agent profile
     * @param delegationDepth current delegation depth
     * @return scoped context
     */
    public static ToolExecutionContext of(AgentProfile parentProfile, int delegationDepth) {
        Objects.requireNonNull(parentProfile, "parentProfile"); //$NON-NLS-1$
        return new ToolExecutionContext(
                parentProfile.getId(),
                ProfileCapabilities.delegationCeiling(parentProfile),
                delegationDepth);
    }

    /**
     * Returns whether this context identifies a trusted parent profile.
     *
     * @return {@code true} for agent-loop calls
     */
    public boolean isScoped() {
        return !UNSCOPED_PROFILE.equals(parentProfileId);
    }
}
