/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Locks case-insensitive prefix and separator normalization for raw secret aliases. */
@RunWith(Parameterized.class)
public class InlineSecretAliasTest {
    @Parameterized.Parameters(name = "{0}:{1}")
    public static Collection<Object[]> aliases() {
        return Arrays.asList(new Object[][] {
                { "environment", "CODEPILOT_PROVIDER-API-KEY" }, //$NON-NLS-1$ //$NON-NLS-2$
                { "environment", "codepilot_provider.api_key" }, //$NON-NLS-1$ //$NON-NLS-2$
                { "environment", "CoDePiLoT-provider_api-key" }, //$NON-NLS-1$ //$NON-NLS-2$
                { "property", "CodePilot-PROVIDER_API_KEY" }, //$NON-NLS-1$ //$NON-NLS-2$
                { "property", "CODEPILOT_provider-api.key" }, //$NON-NLS-1$ //$NON-NLS-2$
                { "property", "codepilot.PROVIDER_api-key" }, //$NON-NLS-1$ //$NON-NLS-2$
                { "config", "OpenAI-Api_Key" }, //$NON-NLS-1$ //$NON-NLS-2$
                { "config", "provider.api-key" } //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    private final String source;
    private final String key;

    public InlineSecretAliasTest(String source, String key) {
        this.source = source;
        this.key = key;
    }

    @Test
    public void rejectsInlineAliasWithoutEchoingValue() throws Exception {
        Path config = Files.createTempFile("runtime-config-", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(config, "config".equals(source) ? key + "=alias-secret\n" : "provider.model=safe-model\n", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                StandardCharsets.UTF_8);
        RuntimeConfigurationLoader loader = RuntimeConfigurationLoader.builder().configFile(config);
        if ("environment".equals(source)) { //$NON-NLS-1$
            loader.environment(Map.of(key, "alias-secret")); //$NON-NLS-1$
        } else if ("property".equals(source)) { //$NON-NLS-1$
            loader.systemProperties(Map.of(key, "alias-secret")); //$NON-NLS-1$
        }
        try {
            loader.load();
            fail("raw secret alias must be rejected"); //$NON-NLS-1$
        } catch (ConfigurationException exception) {
            assertEquals("config".equals(source) ? ConfigurationErrorCode.INVALID_CONFIG_FILE //$NON-NLS-1$
                    : ConfigurationErrorCode.INVALID_VALUE, exception.code());
            assertEquals("config", exception.setting()); //$NON-NLS-1$
            org.junit.Assert.assertFalse(exception.getMessage().contains("alias-secret")); //$NON-NLS-1$
        }
    }
}
