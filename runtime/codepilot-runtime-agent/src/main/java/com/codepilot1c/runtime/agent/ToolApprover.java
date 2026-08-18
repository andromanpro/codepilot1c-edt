/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Asynchronous host policy invoked before each valid tool execution. */
@FunctionalInterface
public interface ToolApprover {
    ToolApprover ALLOW = (call, definition, cancellation) ->
            CompletableFuture.completedFuture(Decision.ALLOW);
    ToolApprover ALLOW_ALL = ALLOW;

    CompletionStage<Decision> approve(
            ToolCall call, ToolDefinition definition, CancellationToken cancellation);

    /** Approval outcome. Denials carry a non-blank, host-facing reason. */
    record Decision(Outcome outcome, String reason) {
        public static final Decision ALLOW = new Decision(Outcome.ALLOW, ""); //$NON-NLS-1$

        public enum Outcome { ALLOW, DENY }

        public Decision {
            Objects.requireNonNull(outcome, "outcome"); //$NON-NLS-1$
            reason = reason == null ? "" : reason; //$NON-NLS-1$
            if (outcome == Outcome.DENY && reason.isBlank()) {
                throw new IllegalArgumentException("denial reason must not be blank"); //$NON-NLS-1$
            }
            if (outcome == Outcome.ALLOW && !reason.isEmpty()) {
                throw new IllegalArgumentException("allow decision must not have a reason"); //$NON-NLS-1$
            }
        }

        public static Decision allow() {
            return ALLOW;
        }

        public static Decision deny(String reason) {
            return new Decision(Outcome.DENY, reason);
        }

        public boolean allowed() {
            return outcome == Outcome.ALLOW;
        }
    }
}
