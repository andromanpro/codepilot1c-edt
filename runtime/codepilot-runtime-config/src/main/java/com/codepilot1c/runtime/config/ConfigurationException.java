/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

/** Deterministic configuration error that never embeds supplied secret values. */
public final class ConfigurationException extends IllegalArgumentException {
    private final ConfigurationErrorCode code;
    private final String setting;

    ConfigurationException(ConfigurationErrorCode code, String setting, String detail) {
        super(code.name() + " setting=" + setting + " detail=" + detail); //$NON-NLS-1$ //$NON-NLS-2$
        this.code = code;
        this.setting = setting;
    }

    /** @return stable error category */
    public ConfigurationErrorCode code() {
        return code;
    }

    /** @return affected external setting name */
    public String setting() {
        return setting;
    }
}
