/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

/**
 * Small lifecycle boundary for the official Eclipse application.
 *
 * <p>The boundary keeps the blocking and stop signal out of the Eclipse entry point and makes
 * the application lifecycle testable without starting EDT or a network transport.</p>
 */
public interface HeadlessApplicationCoordinator {

    /** Starts the application-owned runtime services. */
    void start() throws Exception;

    /** Blocks until {@link #stop()} requests normal application shutdown. */
    void awaitStop() throws InterruptedException;

    /** Requests normal shutdown and releases any wait in {@link #awaitStop()}. */
    void stop();
}
