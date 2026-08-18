/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/**
 * Official Eclipse application entry point for the CodePilot headless EDT host.
 */
public final class HeadlessApplication implements IApplication {

    private final HeadlessApplicationCoordinator coordinator;

    /** Creates the production application backed by core lifecycle services. */
    public HeadlessApplication() {
        this(new CoreHeadlessApplicationCoordinator());
    }

    /**
     * Constructor for lifecycle tests and other controlled launchers.
     *
     * @param coordinator injected lifecycle boundary
     */
    public HeadlessApplication(HeadlessApplicationCoordinator coordinator) {
        if (coordinator == null) {
            throw new IllegalArgumentException("coordinator must not be null"); //$NON-NLS-1$
        }
        this.coordinator = coordinator;
    }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        HeadlessModeSignal signal = HeadlessModeSignal.activate();
        try {
            coordinator.start();
            coordinator.awaitStop();
            return IApplication.EXIT_OK;
        } finally {
            coordinator.stop();
            signal.restore();
        }
    }

    @Override
    public void stop() {
        coordinator.stop();
    }
}
