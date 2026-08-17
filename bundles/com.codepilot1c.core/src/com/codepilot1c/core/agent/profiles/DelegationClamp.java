/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Pure capability clamp for parent-to-child profile delegation.
 */
public final class DelegationClamp {

    public static final String REASON_CAPABILITY_EXCEEDED =
            "delegation_capability_exceeded"; //$NON-NLS-1$
    public static final String REASON_AUTO_CLAMPED =
            "delegation_auto_clamped_to_read_only"; //$NON-NLS-1$
    public static final String REASON_DEPTH_EXCEEDED =
            "delegation_depth_exceeded"; //$NON-NLS-1$
    public static final String REASON_TARGET_UNRESOLVED =
            "delegation_target_unresolved"; //$NON-NLS-1$

    private DelegationClamp() {
    }

    public enum Outcome {
        ALLOWED,
        CLAMPED,
        DENIED
    }

    /**
     * Immutable clamp decision.
     *
     * @param outcome decision outcome
     * @param effectiveProfileId profile to execute, or attempted profile on denial
     * @param reasonCode stable reason code, blank for an allowed request
     * @param parentCeiling parent delegation ceiling
     * @param requiredCapability capability required by the resolved target
     */
    public record Decision(
            Outcome outcome,
            String effectiveProfileId,
            String reasonCode,
            AgentCapability parentCeiling,
            AgentCapability requiredCapability) {
    }

    /**
     * Applies the parent ceiling after profile routing.
     *
     * @param parentCeiling trusted parent ceiling
     * @param autoRequested whether the target came from auto routing
     * @param requestedProfileId normalized model request
     * @param resolved resolved target profile, or {@code null}
     * @param lookupFallback resolves the deterministic read-only fallback id
     * @return immutable decision
     */
    public static Decision decide(
            AgentCapability parentCeiling,
            boolean autoRequested,
            String requestedProfileId,
            AgentProfile resolved,
            UnaryOperator<String> lookupFallback) {
        Objects.requireNonNull(parentCeiling, "parentCeiling"); //$NON-NLS-1$
        String requested = requestedProfileId != null ? requestedProfileId : ""; //$NON-NLS-1$
        if (resolved == null) {
            return new Decision(
                    Outcome.DENIED,
                    requested,
                    REASON_TARGET_UNRESOLVED,
                    parentCeiling,
                    AgentCapability.MUTATING);
        }

        AgentCapability required = ProfileCapabilities.requiredForChild(resolved);
        if (parentCeiling.covers(required)) {
            return new Decision(
                    Outcome.ALLOWED,
                    resolved.getId(),
                    "", //$NON-NLS-1$
                    parentCeiling,
                    required);
        }

        if (autoRequested) {
            String fallback = lookupFallback != null
                    ? lookupFallback.apply(ExploreAgentProfile.ID)
                    : ExploreAgentProfile.ID;
            if (fallback == null || fallback.isBlank()) {
                return new Decision(
                        Outcome.DENIED,
                        resolved.getId(),
                        REASON_TARGET_UNRESOLVED,
                        parentCeiling,
                        required);
            }
            return new Decision(
                    Outcome.CLAMPED,
                    fallback,
                    REASON_AUTO_CLAMPED,
                    parentCeiling,
                    required);
        }

        return new Decision(
                Outcome.DENIED,
                resolved.getId(),
                REASON_CAPABILITY_EXCEEDED,
                parentCeiling,
                required);
    }
}
