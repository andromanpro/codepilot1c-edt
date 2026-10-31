/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic terminal value for every agent run. */
public record AgentResult(Status status, Optional<String> text, List<AgentMessage> transcript,
        int steps, Optional<AgentError> error) {
    public enum Status { COMPLETED, FAILED, CANCELLED, TIMED_OUT, STEP_LIMIT }

    public AgentResult {
        Objects.requireNonNull(status, "status"); //$NON-NLS-1$
        Objects.requireNonNull(text, "text"); //$NON-NLS-1$
        Objects.requireNonNull(transcript, "transcript"); //$NON-NLS-1$
        Objects.requireNonNull(error, "error"); //$NON-NLS-1$
        transcript = List.copyOf(transcript);
        if (steps < 0) throw new IllegalArgumentException("steps must not be negative"); //$NON-NLS-1$
        if ((status == Status.COMPLETED) == error.isPresent()) {
            throw new IllegalArgumentException("completed result must have no error; other statuses require one"); //$NON-NLS-1$
        }
    }
}
