/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.wiring.ServiceInitialization;

/**
 * Drives 1C:EDT's own supported non-UI initialization before the headless host is exposed.
 *
 * <p>1C:EDT publishes its global services from bundles contributed to the
 * {@code com._1c.g5.wiring.serviceProvider} extension point. Those bundles are activated by
 * {@code com._1c.g5.wiring} only after someone calls the exported
 * {@link ServiceInitialization#startInitialization()} - the product ships with
 * {@code -De1c.wiring.managedInitialization=true}, which defers activation until that call.
 * 1C:EDT's own auto-started {@code com.e1c.g5.dt.core.start} bundle makes exactly this call once
 * the {@code IWorkspace} OSGi service appears. Requesting it here removes the dependency on that
 * race for a host that must answer readiness probes deterministically, and it uses the same
 * supported entry point rather than a private one.</p>
 *
 * <p>Deliberately <em>not</em> {@code Bundle.start()}: resolving a bundle through
 * {@code FrameworkUtil.getBundle(serviceInterface)} finds the bundle that <em>exports the
 * interface</em>, not the one that publishes the implementation, and starting bundles by hand
 * bypasses the wiring and lifecycle ordering 1C:EDT depends on.</p>
 *
 * <p>Supported-version constraint: {@code com._1c.g5.wiring} package version {@code 2.4.0} or
 * later, which is what 1C:EDT 2026.2.0.289 ships. On a runtime without it the class is missing and
 * {@link #activate()} fails explicitly instead of leaving the host permanently not ready.</p>
 */
public class EdtRuntimeBootstrap {

    private final Supplier<Object> workspaceSupplier;
    private final Runnable managedInitialization;
    private final AtomicBoolean initializationRequested = new AtomicBoolean();

    /** Creates the production bootstrap backed by the Eclipse workspace and 1C:EDT wiring. */
    public EdtRuntimeBootstrap() {
        this(ResourcesPlugin::getWorkspace, ServiceInitialization::startInitialization);
    }

    /**
     * @param workspaceSupplier supplies the Eclipse workspace, or {@code null} when unavailable
     * @param managedInitialization requests 1C:EDT managed initialization
     */
    EdtRuntimeBootstrap(Supplier<Object> workspaceSupplier, Runnable managedInitialization) {
        this.workspaceSupplier = workspaceSupplier;
        this.managedInitialization = managedInitialization;
    }

    /**
     * Opens the workspace and requests 1C:EDT managed initialization once.
     *
     * @throws EdtRuntimeBootstrapException when no Eclipse workspace is available, or when this
     *     1C:EDT runtime does not expose the supported managed-initialization entry point
     */
    public void activate() {
        // Touching ResourcesPlugin both proves the workspace is usable and publishes the
        // IWorkspace service the EDT startup chain waits for.
        Object workspace;
        try {
            workspace = workspaceSupplier.get();
        } catch (RuntimeException | LinkageError e) {
            throw new EdtRuntimeBootstrapException(
                    "Eclipse workspace is unavailable for the headless EDT bootstrap", e); //$NON-NLS-1$
        }
        if (workspace == null) {
            throw new EdtRuntimeBootstrapException(
                    "Eclipse workspace is unavailable for the headless EDT bootstrap"); //$NON-NLS-1$
        }

        if (!initializationRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            managedInitialization.run();
        } catch (RuntimeException | LinkageError e) {
            initializationRequested.set(false);
            throw new EdtRuntimeBootstrapException(
                    "EDT managed initialization is unavailable in this EDT runtime", e); //$NON-NLS-1$
        }
    }
}
