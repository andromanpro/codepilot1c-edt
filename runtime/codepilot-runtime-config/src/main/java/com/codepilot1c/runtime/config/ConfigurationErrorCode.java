/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

/** Stable categories for configuration failures safe to show to a user. */
public enum ConfigurationErrorCode {
    INVALID_VALUE,
    INVALID_ENDPOINT,
    INVALID_CONFIG_FILE,
    UNSAFE_CONFIG_FILE,
    UNSAFE_SECRET_FILE,
    SECRET_UNAVAILABLE,
    SECRET_TOO_LARGE
}
