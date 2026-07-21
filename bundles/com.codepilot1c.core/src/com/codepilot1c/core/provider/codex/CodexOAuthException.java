/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

/**
 * Raised when the Codex OAuth login flow fails (state mismatch, token exchange failure,
 * missing account id, etc.).
 */
public class CodexOAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodexOAuthException(String message) {
        super(message);
    }

    public CodexOAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
