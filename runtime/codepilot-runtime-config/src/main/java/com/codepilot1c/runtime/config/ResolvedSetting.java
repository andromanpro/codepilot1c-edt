/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.util.Objects;

/** A typed, non-secret setting together with the source that won precedence. */
public record ResolvedSetting<T>(T value, ConfigurationSource source) {
    public ResolvedSetting {
        Objects.requireNonNull(value, "value"); //$NON-NLS-1$
        Objects.requireNonNull(source, "source"); //$NON-NLS-1$
    }
}
