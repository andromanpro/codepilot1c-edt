/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.bm.integration.IBmPlatformGlobalEditingContext;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

/**
 * Actively activates the EDT bundles that publish the mutation-readiness services.
 *
 * <p>GUI workbench startup implicitly triggers OSGi lazy activation of these bundles by loading
 * their classes from workbench extension points (editors, wizards, views). The headless
 * application never touches those extension points, so the bundles stay resolved but never
 * active and their declared services never register with the framework. This boundary
 * reproduces that activation explicitly, using only public OSGi API ({@link FrameworkUtil} to
 * find the bundle that exports each required service interface, {@link Bundle#start(int)} to
 * activate it) so it stays correct across EDT releases without hard-coding bundle symbolic
 * names or versions.</p>
 */
public class EdtRuntimeBootstrap {

    private static final List<String> REQUIRED_IMPLEMENTATION_BUNDLE_NAMES = List.of(
            "com._1c.g5.v8.dt.core"); //$NON-NLS-1$

    private static final List<Class<?>> REQUIRED_SERVICE_TYPES = List.of(
            IConfigurationProvider.class,
            IDtProjectManager.class,
            IDerivedDataManagerProvider.class,
            IBmModelManager.class,
            IBmPlatformGlobalEditingContext.class);

    /**
     * Starts the bundle exporting each required EDT service interface.
     *
     * @throws EdtRuntimeBootstrapException if a bundle exporting a required service type cannot
     *     be resolved, or fails to activate
     */
    public void activate() {
        for (String symbolicName : REQUIRED_IMPLEMENTATION_BUNDLE_NAMES) {
            activateBundle(symbolicName);
        }
        for (Class<?> serviceType : REQUIRED_SERVICE_TYPES) {
            activateOwningBundle(serviceType);
        }
    }

    private void activateBundle(String symbolicName) {
        Bundle self = FrameworkUtil.getBundle(EdtRuntimeBootstrap.class);
        if (self == null || self.getBundleContext() == null) {
            throw new EdtRuntimeBootstrapException(
                    "No OSGi bundle context is available for EDT runtime bootstrap"); //$NON-NLS-1$
        }
        Bundle owner = findBundle(self.getBundleContext(), symbolicName);
        if (owner == null) {
            throw new EdtRuntimeBootstrapException(
                    "No bundle named " + symbolicName); //$NON-NLS-1$
        }
        activate(owner, symbolicName);
    }

    private void activateOwningBundle(Class<?> serviceType) {
        Bundle owner = FrameworkUtil.getBundle(serviceType);
        if (owner == null) {
            throw new EdtRuntimeBootstrapException(
                    "No bundle exports " + serviceType.getName()); //$NON-NLS-1$
        }
        activate(owner, serviceType.getName());
    }

    private static Bundle findBundle(BundleContext context, String symbolicName) {
        for (Bundle bundle : context.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }

    private static void activate(Bundle owner, String purpose) {
        try {
            owner.start(Bundle.START_TRANSIENT);
        } catch (BundleException e) {
            throw new EdtRuntimeBootstrapException(
                    "Failed to activate " + owner.getSymbolicName() //$NON-NLS-1$
                            + " for " + purpose, e); //$NON-NLS-1$
        }
    }
}
