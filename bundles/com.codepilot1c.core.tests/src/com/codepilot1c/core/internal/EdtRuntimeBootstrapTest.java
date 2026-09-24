/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Contract for the headless 1C:EDT bootstrap.
 *
 * <p>The bootstrap must drive 1C:EDT's own supported non-UI initialization
 * ({@code com._1c.g5.wiring.ServiceInitialization#startInitialization()}, the entry point
 * 1C:EDT's auto-started {@code com.e1c.g5.dt.core.start} bundle calls once the {@code IWorkspace}
 * service appears) instead of starting 1C:EDT bundles directly. Direct {@code Bundle.start()}
 * activates the bundles that <em>export</em> the service interfaces rather than the ones that
 * publish the implementations, and it bypasses the wiring/lifecycle ordering that 1C:EDT relies
 * on.</p>
 */
public class EdtRuntimeBootstrapTest {

    @Test
    public void requestsEdtManagedInitializationOnceTheWorkspaceIsAvailable() {
        List<String> order = new ArrayList<>();
        EdtRuntimeBootstrap bootstrap = new EdtRuntimeBootstrap(
                () -> {
                    order.add("workspace"); //$NON-NLS-1$
                    return new Object();
                },
                () -> order.add("managedInitialization")); //$NON-NLS-1$

        bootstrap.activate();

        assertEquals(List.of("workspace", "managedInitialization"), order); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void failsWithANamedReasonAndNeverInitializesWithoutAWorkspace() {
        List<String> order = new ArrayList<>();
        EdtRuntimeBootstrap bootstrap = new EdtRuntimeBootstrap(
                () -> null,
                () -> order.add("managedInitialization")); //$NON-NLS-1$

        try {
            bootstrap.activate();
        } catch (EdtRuntimeBootstrapException expected) {
            assertTrue("the failure must name the missing runtime piece: " + expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("workspace")); //$NON-NLS-1$
            assertEquals(List.of(), order);
            return;
        }
        fail("Expected the bootstrap to fail without an Eclipse workspace"); //$NON-NLS-1$
    }

    @Test
    public void repeatedActivationRequestsInitializationOnlyOnce() {
        List<String> order = new ArrayList<>();
        EdtRuntimeBootstrap bootstrap = new EdtRuntimeBootstrap(
                Object::new,
                () -> order.add("managedInitialization")); //$NON-NLS-1$

        bootstrap.activate();
        bootstrap.activate();

        assertEquals(List.of("managedInitialization"), order); //$NON-NLS-1$
    }

    @Test
    public void surfacesAnInitializationFailureAsABootstrapFailure() {
        EdtRuntimeBootstrap bootstrap = new EdtRuntimeBootstrap(
                Object::new,
                () -> {
                    throw new NoClassDefFoundError("com/_1c/g5/wiring/ServiceInitialization"); //$NON-NLS-1$
                });

        try {
            bootstrap.activate();
        } catch (EdtRuntimeBootstrapException expected) {
            assertTrue("an unsupported EDT runtime must be reported explicitly: " //$NON-NLS-1$
                    + expected.getMessage(),
                    expected.getMessage().contains("managed initialization")); //$NON-NLS-1$
            return;
        }
        fail("Expected the bootstrap to fail when EDT managed initialization is unavailable"); //$NON-NLS-1$
    }
}
