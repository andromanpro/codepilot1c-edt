/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

/** One monitor for the process-wide tool/descriptor registry composition. */
public final class RegistryPairLifecycle {
    public static final Object LOCK = new Object();

    private RegistryPairLifecycle() {
    }
}
