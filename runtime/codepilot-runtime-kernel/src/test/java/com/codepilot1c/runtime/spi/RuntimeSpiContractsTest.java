/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.spi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import com.codepilot1c.runtime.spi.LogSink.Event;
import com.codepilot1c.runtime.spi.LogSink.Level;
import com.codepilot1c.runtime.spi.ProviderFactoryRegistry.ProviderFactory;
import com.codepilot1c.runtime.spi.ProviderFactoryRegistry.ProviderTypeId;
import com.codepilot1c.runtime.spi.WorkspaceContextPort.Context;
import com.codepilot1c.runtime.spi.WorkspaceContextPort.Project;

/** Contract-level tests for the first runtime SPI slice. */
public class RuntimeSpiContractsTest {

    @Test
    public void logSinkReceivesTypedEvents() {
        List<Event> events = new ArrayList<>();
        LogSink sink = events::add;
        IllegalStateException failure = new IllegalStateException("offline"); //$NON-NLS-1$

        sink.log(Event.message(Level.INFO, "runtime", "started")); //$NON-NLS-1$ //$NON-NLS-2$
        sink.log(Event.failure(Level.ERROR, "provider", "failed", failure)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, events.size());
        assertEquals(Level.INFO, events.get(0).level());
        assertFalse(events.get(0).cause().isPresent());
        assertSame(failure, events.get(1).cause().orElseThrow());
    }

    @Test
    public void settingsAndSecretsRemainStorageNeutral() {
        Map<String, String> settings = new HashMap<>();
        SettingsStore settingsStore = mapSettingsStore(settings);
        CopyingSecretStore secretStore = new CopyingSecretStore();

        settingsStore.write("runtime.profile", "build"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("build", settingsStore.read("runtime.profile").orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
        settingsStore.remove("runtime.profile"); //$NON-NLS-1$
        assertTrue(settingsStore.read("runtime.profile").isEmpty()); //$NON-NLS-1$

        char[] supplied = "token".toCharArray(); //$NON-NLS-1$
        secretStore.write("provider.apiKey", supplied); //$NON-NLS-1$
        Arrays.fill(supplied, 'x');
        char[] firstRead = secretStore.read("provider.apiKey").orElseThrow(); //$NON-NLS-1$
        char[] secondRead = secretStore.read("provider.apiKey").orElseThrow(); //$NON-NLS-1$
        assertArrayEquals("token".toCharArray(), firstRead); //$NON-NLS-1$
        assertNotSame(firstRead, secondRead);
    }

    @Test
    public void toolAndProviderCatalogsKeepHostTypes() {
        ToolCatalog<Integer> tools = new ToolCatalog<>() {
            @Override
            public List<Integer> snapshot() {
                return List.of(7);
            }

            @Override
            public Optional<Integer> find(String name) {
                return "example".equals(name) ? Optional.of(7) : Optional.empty(); //$NON-NLS-1$
            }
        };
        ProviderTypeId type = new ProviderTypeId("test-provider"); //$NON-NLS-1$
        ProviderFactory<String, Integer> factory = String::length;
        ProviderFactoryRegistry<String, Integer> providers = new ProviderFactoryRegistry<>() {
            @Override
            public Set<ProviderTypeId> types() {
                return Set.of(type);
            }

            @Override
            public Optional<ProviderFactory<String, Integer>> find(ProviderTypeId requestedType) {
                return type.equals(requestedType) ? Optional.of(factory) : Optional.empty();
            }
        };

        assertEquals(Integer.valueOf(7), tools.find("example").orElseThrow()); //$NON-NLS-1$
        assertEquals(Integer.valueOf(6), providers.find(type).orElseThrow().create("config")); //$NON-NLS-1$
        assertEquals(Set.of(type), providers.types());
    }

    @Test
    public void workspaceSnapshotUsesPlainFilesystemIdentity() {
        Path workspace = Path.of(System.getProperty("java.io.tmpdir"), "workspace").toAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$
        Project project = new Project("demo", workspace.resolve("projects/../demo")); //$NON-NLS-1$ //$NON-NLS-2$
        WorkspaceContextPort port = () -> new Context(workspace, Optional.of(project));

        Context context = port.snapshot();

        assertEquals(workspace.normalize(), context.workspaceRoot());
        assertEquals(workspace.resolve("demo"), context.activeProject().orElseThrow().root()); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void workspaceRootMustBeAbsolute() {
        new Context(Path.of("relative"), Optional.empty()); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void providerTypeRejectsAmbiguousWhitespace() {
        new ProviderTypeId(" provider "); //$NON-NLS-1$
    }

    private static SettingsStore mapSettingsStore(Map<String, String> values) {
        return new SettingsStore() {
            @Override
            public Optional<String> read(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public void write(String key, String value) {
                values.put(key, value);
            }

            @Override
            public void remove(String key) {
                values.remove(key);
            }
        };
    }

    private static final class CopyingSecretStore implements SecretStore {
        private final Map<String, char[]> values = new HashMap<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<char[]> read(String key) {
            char[] value = values.get(key);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void write(String key, char[] value) {
            values.put(key, value.clone());
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
