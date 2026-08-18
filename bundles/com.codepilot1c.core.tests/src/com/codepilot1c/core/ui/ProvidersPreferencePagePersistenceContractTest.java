/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class ProvidersPreferencePagePersistenceContractTest {

    @Test
    public void performOkStaysOpenAndShowsFixedErrorWhenProviderSaveAborts() throws Exception {
        String source = readRepoFile(
                "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/ProvidersPreferencePage.java"); //$NON-NLS-1$
        String performOk = extractMethod(source, "public boolean performOk()"); //$NON-NLS-1$

        assertTrue(performOk.contains("boolean saved = store.saveProviders(providers, activeProviderId);")); //$NON-NLS-1$
        assertTrue(performOk.contains("if (!saved)")); //$NON-NLS-1$
        assertTrue(performOk.contains("setErrorMessage(Messages.ProvidersPreferencePage_SaveError);")); //$NON-NLS-1$
        assertTrue(performOk.contains("return false;")); //$NON-NLS-1$
        assertFalse(performOk.contains("MessageDialog.open")); //$NON-NLS-1$
    }

    @Test
    public void persistenceErrorIsActionableAndContainsNoDynamicSensitiveContext() throws Exception {
        String messages = readRepoFile(
                "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/messages.properties"); //$NON-NLS-1$
        String prefix = "ProvidersPreferencePage_SaveError = "; //$NON-NLS-1$
        int start = messages.indexOf(prefix);
        assertTrue(start >= 0);
        int end = messages.indexOf('\n', start);
        String message = messages.substring(start + prefix.length(), end >= 0 ? end : messages.length());

        assertTrue(message.contains("Restore access to secure storage and try again.")); //$NON-NLS-1$
        assertFalse(message.contains("{0}")); //$NON-NLS-1$
        assertFalse(message.toLowerCase().contains("provider id")); //$NON-NLS-1$
        assertFalse(message.toLowerCase().contains("endpoint")); //$NON-NLS-1$
        assertFalse(message.toLowerCase().contains("api key")); //$NON-NLS-1$
        assertFalse(message.toLowerCase().contains("exception")); //$NON-NLS-1$
    }

    @Test
    public void codexProviderMutationAlsoChecksPersistenceOutcome() throws Exception {
        String source = readRepoFile(
                "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/CodexAccountPreferencePage.java"); //$NON-NLS-1$
        String ensureActive = extractMethod(source,
                "private boolean ensureActiveCodexProvider(String model, String reasoningEffort)"); //$NON-NLS-1$

        assertTrue(ensureActive.contains("if (!store.addProvider(codex))")); //$NON-NLS-1$
        assertTrue(ensureActive.contains("if (!store.updateProvider(codex))")); //$NON-NLS-1$
        assertTrue(ensureActive.contains("return store.setActiveProviderId(codex.getId());")); //$NON-NLS-1$
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

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath(); //$NON-NLS-1$
        while (current != null) {
            if (Files.exists(current.resolve(
                    "bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/ProvidersPreferencePage.java"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found"); //$NON-NLS-1$
    }
}
