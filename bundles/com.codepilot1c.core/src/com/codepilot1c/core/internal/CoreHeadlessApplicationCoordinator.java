/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import com.codepilot1c.core.mcp.host.McpHostManager;

/**
 * Core lifecycle adapter used by {@link HeadlessApplication} in a real Eclipse runtime.
 */
public final class CoreHeadlessApplicationCoordinator implements HeadlessApplicationCoordinator {

    private final CountDownLatch stopLatch;
    private final Supplier<McpHostManager.ApplicationHostLease> hostStarter;
    private McpHostManager.ApplicationHostLease applicationHost;

    /** Creates the production coordinator backed by the core plug-in lifecycle. */
    public CoreHeadlessApplicationCoordinator() {
        this(new CountDownLatch(1), VibeCorePlugin::startHeadlessMcpHost);
    }

    CoreHeadlessApplicationCoordinator(
            CountDownLatch stopLatch,
            Supplier<McpHostManager.ApplicationHostLease> hostStarter) {
        this.stopLatch = stopLatch;
        this.hostStarter = hostStarter;
    }

    @Override
    public synchronized void start() {
        if (applicationHost == null) {
            try {
                applicationHost = hostStarter.get();
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to start headless MCP host", e); //$NON-NLS-1$
            }
            if (applicationHost == null) {
                throw new IllegalStateException("Headless MCP host did not return an application lease"); //$NON-NLS-1$
            }
        }
    }

    @Override
    public void awaitStop() throws InterruptedException {
        stopLatch.await();
    }

    @Override
    public void stop() {
        McpHostManager.ApplicationHostLease host;
        synchronized (this) {
            host = applicationHost;
            applicationHost = null;
        }
        if (host != null) {
            host.close();
        }
        stopLatch.countDown();
    }
}
