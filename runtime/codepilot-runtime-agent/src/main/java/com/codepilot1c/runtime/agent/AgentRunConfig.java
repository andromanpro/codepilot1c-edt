/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.time.Duration;
import java.util.Objects;

/** Safety bounds for one agent run. */
public record AgentRunConfig(int maxSteps, Duration timeout) {
    public AgentRunConfig {
        if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps must be positive"); //$NON-NLS-1$
        Objects.requireNonNull(timeout, "timeout"); //$NON-NLS-1$
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive"); //$NON-NLS-1$
        }
        try {
            timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow); //$NON-NLS-1$
        }
    }

    public static AgentRunConfig defaults() {
        return new AgentRunConfig(16, Duration.ofMinutes(5));
    }
}
