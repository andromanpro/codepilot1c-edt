/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.Objects;

/**
 * Central capability derivation for agent profiles.
 */
public final class ProfileCapabilities {

    private ProfileCapabilities() {
    }

    /**
     * Derives what a profile can do directly.
     *
     * @param profile profile to inspect
     * @return execution capability
     */
    public static AgentCapability executionCapability(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
        return profile.isReadOnly() && !profile.canExecuteShell()
                ? AgentCapability.READ_ONLY
                : AgentCapability.MUTATING;
    }

    /**
     * Returns the strongest capability that a profile may delegate.
     *
     * @param profile profile to inspect
     * @return delegation ceiling
     */
    public static AgentCapability delegationCeiling(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
        AgentCapability ceiling = profile.getDelegationCeiling();
        return ceiling != null ? ceiling : executionCapability(profile);
    }

    /**
     * Returns the capability needed to create a child with this profile. A
     * broker therefore requires its delegation ceiling, even when its own
     * execution surface is read-only.
     *
     * @param profile prospective child profile
     * @return required parent capability
     */
    public static AgentCapability requiredForChild(AgentProfile profile) {
        return AgentCapability.max(executionCapability(profile), delegationCeiling(profile));
    }
}
