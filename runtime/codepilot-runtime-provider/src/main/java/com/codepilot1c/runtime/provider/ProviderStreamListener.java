/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

/** Receives ordered events from one provider stream. */
@FunctionalInterface
public interface ProviderStreamListener {

    /**
     * Handles one event. Calls are serialized, but may run on an HTTP-client
     * or worker thread and should return promptly.
     *
     * @param event next stream event
     */
    void onEvent(ProviderStreamEvent event);
}
