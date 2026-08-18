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
 * Raw response from an OpenAI-compatible chat-completions endpoint.
 *
 * <p>Response parsing is intentionally not frozen in this first runtime
 * slice. A future agent model adapter can map the raw body into its own
 * response types without coupling this transport to Eclipse core classes.</p>
 */
public record ChatCompletionResponse(int statusCode, String body) {

    /** Validates the HTTP response contract. */
    public ChatCompletionResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be an HTTP status"); //$NON-NLS-1$
        }
        Objects.requireNonNull(body, "body"); //$NON-NLS-1$
    }

    /** @return true for a 2xx API response */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }
}
