/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Documents and locks the compile-time dependency inventory of this slice. */
public class ProviderDependencyInventoryTest {

    @Test
    public void compileScopeContainsOnlyRuntimeKernel() throws IOException {
        Path pom = Path.of(System.getProperty("runtime.module.basedir"), "pom.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        String source = Files.readString(pom);

        assertTrue(source.contains("<artifactId>codepilot-runtime-kernel</artifactId>")); //$NON-NLS-1$
        assertFalse(source.contains("org.eclipse")); //$NON-NLS-1$
        assertFalse(source.contains("org.osgi")); //$NON-NLS-1$
        assertFalse(source.contains("com.codepilot1c.core")); //$NON-NLS-1$
    }
}
