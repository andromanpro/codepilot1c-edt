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
    private final int httpStatus;

    public AgentModelException(Kind kind, String message) {
        this(kind, message, -1, null);
    }

    public AgentModelException(Kind kind, String message, Throwable cause) {
        this(kind, message, -1, cause);
    }

    /** Creates an HTTP failure without exposing the response body. */
    public AgentModelException(Kind kind, String message, int httpStatus) {
        this(kind, message, httpStatus, null);
    }

    private AgentModelException(Kind kind, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
        if (kind == Kind.HTTP && httpStatus != -1 && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("HTTP failure requires a valid status"); //$NON-NLS-1$
        }
        if (kind != Kind.HTTP && httpStatus != -1) {
            throw new IllegalArgumentException("Only HTTP failures have a status"); //$NON-NLS-1$
        }
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    /** @return HTTP status, or {@code -1} for non-HTTP failures */
    public int httpStatus() {
        return httpStatus;
    }
}
