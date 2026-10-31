/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Platform-neutral service provider interfaces used to assemble a CodePilot
 * runtime host.
 *
 * <p>The package deliberately contains no provider implementations, tool
 * execution model, persistence format, or IDE lifecycle contract. Platform
 * adapters own those decisions until their production consumers are migrated
 * and the API has completed review.</p>
 */
package com.codepilot1c.runtime.spi;
