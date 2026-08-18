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
    private final AgentError.Code agentErrorCode;

    public AgentModelException(Kind kind, String message) {
        this(kind, message, -1, null, null);
    }

    public AgentModelException(Kind kind, String message, Throwable cause) {
        this(kind, message, -1, cause, null);
    }

    /** Creates an HTTP failure without exposing the response body. */
    public AgentModelException(Kind kind, String message, int httpStatus) {
        this(kind, message, httpStatus, null, null);
    }

    /** Creates a failure carrying the frozen broker's typed {@link AgentError.Code}. */
    public AgentModelException(AgentError.Code code, String message, int httpStatus) {
        this(kindFor(code), message, httpStatus, null, Objects.requireNonNull(code, "code")); //$NON-NLS-1$
    }

    private AgentModelException(Kind kind, String message, int httpStatus, Throwable cause,
            AgentError.Code agentErrorCode) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
        if (httpStatus != -1 && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("HTTP failure requires a valid status"); //$NON-NLS-1$
        }
        if (kind != Kind.HTTP && httpStatus != -1 && agentErrorCode == null) {
            throw new IllegalArgumentException("Only HTTP failures have a status"); //$NON-NLS-1$
        }
        this.httpStatus = httpStatus;
        this.agentErrorCode = agentErrorCode;
    }

    public Kind kind() {
        return kind;
    }

    /** @return HTTP status, or {@code -1} for non-HTTP failures */
    public int httpStatus() {
        return httpStatus;
    }

    /** @return broker-provided typed code, or {@code null} for legacy adapters */
    public AgentError.Code agentErrorCode() {
        return agentErrorCode;
    }

    private static Kind kindFor(AgentError.Code code) {
        return switch (code) {
            case PROVIDER_AUTH, PROVIDER_HTTP -> Kind.HTTP;
            case PROVIDER_RESPONSE -> Kind.MALFORMED_RESPONSE;
            default -> Kind.TRANSPORT;
        };
    }
}
