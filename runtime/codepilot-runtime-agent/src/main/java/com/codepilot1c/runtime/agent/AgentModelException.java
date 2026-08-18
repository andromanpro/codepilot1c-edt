/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.Objects;

/** Typed provider-adapter failure. Messages must not contain request bodies or credentials. */
public final class AgentModelException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Kind { TRANSPORT, HTTP, MALFORMED_RESPONSE }

    private final Kind kind;

    public AgentModelException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
    }

    public AgentModelException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
    }

    public Kind kind() {
        return kind;
    }
}
