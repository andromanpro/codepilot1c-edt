/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.time.Duration;
import java.util.Objects;

/** Independent bounded-agent snapshot for a host integration adapter. */
public record AgentRuntimeSettings(int maxSteps, Duration timeout, ConfigurationSource maxStepsSource,
        ConfigurationSource timeoutSource) {
    public AgentRuntimeSettings {
        if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps must be positive"); //$NON-NLS-1$
        Objects.requireNonNull(timeout, "timeout"); //$NON-NLS-1$
        Objects.requireNonNull(maxStepsSource, "maxStepsSource"); //$NON-NLS-1$
        Objects.requireNonNull(timeoutSource, "timeoutSource"); //$NON-NLS-1$
    }
}
