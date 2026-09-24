/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

/**
 * Thrown when the headless EDT runtime bootstrap cannot activate the bundles that publish the
 * mutation-readiness services.
 */
public final class EdtRuntimeBootstrapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EdtRuntimeBootstrapException(String message) {
        super(message);
    }

    public EdtRuntimeBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
