/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

/** Origin of a resolved setting, ordered from least to most authoritative. */
public enum ConfigurationSource {
    DEFAULT,
    CONFIG_FILE,
    ENVIRONMENT,
    SYSTEM_PROPERTY,
    EXPLICIT
}
