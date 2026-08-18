/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.runtime.adapters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.junit.Test;

public class RuntimeKernelPackagingTest {

    private static final String EMBEDDED_KERNEL = "lib/codepilot-runtime-kernel.jar"; //$NON-NLS-1$
    private static final Set<String> KERNEL_CLASSES = Set.of(
            "com/codepilot1c/runtime/spi/LogSink.class", //$NON-NLS-1$
            "com/codepilot1c/runtime/spi/SettingsStore.class", //$NON-NLS-1$
            "com/codepilot1c/runtime/spi/SecretStore.class"); //$NON-NLS-1$

    @Test
    public void coreBundleContainsKernelJarOnBundleClassPath() throws IOException {
        Path coreBundle = Path.of(System.getProperty("core.bundle.path")); //$NON-NLS-1$
        assertTrue("Core bundle was not built: " + coreBundle, Files.isRegularFile(coreBundle)); //$NON-NLS-1$

        try (JarFile bundle = new JarFile(coreBundle.toFile())) {
            Manifest manifest = bundle.getManifest();
            assertNotNull(manifest);
            String bundleClassPath = manifest.getMainAttributes().getValue("Bundle-ClassPath"); //$NON-NLS-1$
            assertNotNull(bundleClassPath);
            assertTrue(bundleClassPath.contains(EMBEDDED_KERNEL));
            String exportedPackages = manifest.getMainAttributes().getValue("Export-Package"); //$NON-NLS-1$
            assertNotNull(exportedPackages);
            assertFalse(exportedPackages.contains("com.codepilot1c.runtime.spi")); //$NON-NLS-1$
            assertFalse(exportedPackages.contains("com.codepilot1c.core.runtime.adapters")); //$NON-NLS-1$
            assertNotNull(bundle.getJarEntry(EMBEDDED_KERNEL));

            try (InputStream nested = bundle.getInputStream(bundle.getJarEntry(EMBEDDED_KERNEL));
                    JarInputStream kernel = new JarInputStream(nested)) {
                Set<String> missingClasses = new HashSet<>(KERNEL_CLASSES);
                removePresentClasses(kernel, missingClasses);
                assertTrue("Embedded kernel is missing classes: " + missingClasses, missingClasses.isEmpty()); //$NON-NLS-1$
            }
        }
    }

    private static void removePresentClasses(JarInputStream kernel, Set<String> missingClasses) throws IOException {
        for (var entry = kernel.getNextJarEntry(); entry != null; entry = kernel.getNextJarEntry()) {
            missingClasses.remove(entry.getName());
        }
    }
}
