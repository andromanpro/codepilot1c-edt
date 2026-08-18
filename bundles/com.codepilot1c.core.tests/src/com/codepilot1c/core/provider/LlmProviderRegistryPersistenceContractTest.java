/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class LlmProviderRegistryPersistenceContractTest {

    @Test
    public void setActiveProviderPropagatesConfigPersistenceFailureInEveryBranch() throws Exception {
        String source = readRepoFile(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/LlmProviderRegistry.java"); //$NON-NLS-1$
        String setActive = extractMethod(source, "public boolean setActiveProvider(String id)"); //$NON-NLS-1$

        assertEquals(3, countOccurrences(setActive, "if (!configStore.setActiveProviderId(id))")); //$NON-NLS-1$
        assertTrue(setActive.contains("restoreLegacyProviderPreference(prefs, previousLegacyProviderId);")); //$NON-NLS-1$
        assertTrue(setActive.contains("return false;")); //$NON-NLS-1$
        assertTrue(setActive.contains("return true;")); //$NON-NLS-1$
    }

    @Test
    public void registryCallersHandleFalseOutcome() throws Exception {
        String plugin = readRepoFile(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java"); //$NON-NLS-1$
        String accountPage = readRepoFile(
                "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/AccountPreferencePage.java"); //$NON-NLS-1$

        assertTrue(plugin.contains("if (!registry.setActiveProvider(\"backend\"))")); //$NON-NLS-1$
        assertTrue(accountPage.contains("fallbackSelected = LlmProviderRegistry.getInstance()" //$NON-NLS-1$
                + ".setActiveProvider(fallbackProvider.getId());")); //$NON-NLS-1$
        assertTrue(accountPage.contains("setErrorMessage(Messages.ProvidersPreferencePage_SaveError);")); //$NON-NLS-1$
    }

    private static String readRepoFile(String relativePath) throws Exception {
        return Files.readString(repoRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static String extractMethod(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("Method not found: " + signature); //$NON-NLS-1$
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("Method end not found: " + signature); //$NON-NLS-1$
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath(); //$NON-NLS-1$
        while (current != null) {
            if (Files.exists(current.resolve(
                    "bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/LlmProviderRegistry.java"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found"); //$NON-NLS-1$
    }
}
