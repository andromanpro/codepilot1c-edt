/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.util.Objects;

/** One text message in a chat-completions request. */
public record ChatMessage(String role, String content) {

    /** Validates message fields without restricting valid provider roles. */
    public ChatMessage {
        Objects.requireNonNull(role, "role"); //$NON-NLS-1$
        Objects.requireNonNull(content, "content"); //$NON-NLS-1$
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank"); //$NON-NLS-1$
        }
    }
}
