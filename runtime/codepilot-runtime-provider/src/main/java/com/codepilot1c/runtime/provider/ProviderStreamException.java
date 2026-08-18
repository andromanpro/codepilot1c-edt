/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.util.Objects;

/**
 * Typed streaming failure with deliberately body-free diagnostics.
 *
 * <p>Response failures cover an HTTP error, malformed SSE/JSON, or an
 * incomplete OpenAI chunk contract. Transport failures cover failure to send
 * or read the response and a stream that disconnects before its terminal
 * {@code [DONE]} marker.</p>
 */
public final class ProviderStreamException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Stable failure categories for provider-neutral callers. */
    public enum Kind {
        TRANSPORT,
        RESPONSE,
        LISTENER
    }

    private final Kind kind;
    private final int httpStatus;

    ProviderStreamException(Kind kind, String message) {
        this(kind, message, -1);
    }

    ProviderStreamException(Kind kind, String message, int httpStatus) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
        if (httpStatus != -1 && (kind != Kind.RESPONSE || httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("Only response failures may have a valid HTTP status"); //$NON-NLS-1$
        }
        this.httpStatus = httpStatus;
    }

    /** @return transport, response, or listener failure category */
    public Kind kind() {
        return kind;
    }

    /** @return HTTP response status, or {@code -1} when not applicable */
    public int httpStatus() {
        return httpStatus;
    }
}
