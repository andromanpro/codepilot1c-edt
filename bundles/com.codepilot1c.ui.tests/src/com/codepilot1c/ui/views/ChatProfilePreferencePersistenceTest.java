/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.session.Session;

public class ChatProfilePreferencePersistenceTest {

    private static final String KEY = "chat.profileId"; //$NON-NLS-1$

    @Test
    public void putAndFlushMakesSelectionDurable() {
        DurableBackend backend = new DurableBackend();
        TestStore store = backend.open();

        assertTrue(ChatProfilePreferencePersistence.putAndFlush(
                store, KEY, "plan", "persist-plan", failure -> { //$NON-NLS-1$ //$NON-NLS-2$
                    throw new AssertionError(failure);
                }));

        assertEquals(1, store.putCount);
        assertEquals(1, store.flushCount);
        assertEquals("plan", backend.open().get(KEY, "build")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void flushFailureIsReportedAndDoesNotEscapeIntoUiFlow() {
        DurableBackend backend = new DurableBackend();
        TestStore store = backend.open();
        BackingStoreException expected = new BackingStoreException("disk unavailable"); //$NON-NLS-1$
        store.flushFailure = expected;
        AtomicReference<ChatProfilePreferencePersistence.Failure> reported = new AtomicReference<>();

        assertFalse(ChatProfilePreferencePersistence.putAndFlush(
                store, KEY, "explore", "persist-explore", reported::set)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, store.putCount);
        assertEquals(1, store.flushCount);
        assertEquals("build", backend.open().get(KEY, "build")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("persist-explore", reported.get().opId()); //$NON-NLS-1$
        assertEquals(KEY, reported.get().key());
        assertEquals("explore", reported.get().value()); //$NON-NLS-1$
        assertSame(expected, reported.get().cause());
    }

    @Test
    public void freshSessionInheritanceCrossesOnlyTheFlushBoundary() throws Exception {
        DurableBackend backend = new DurableBackend();
        TestStore firstProcess = backend.open();
        firstProcess.put(KEY, "plan"); //$NON-NLS-1$

        assertEquals("", backend.open().get(KEY, "")); //$NON-NLS-1$ //$NON-NLS-2$

        firstProcess.flush();
        TestStore freshProcess = backend.open();
        Session freshSession = new Session("fresh-session"); //$NON-NLS-1$
        String configured = ChatProfilePreferencePersistence.get(freshProcess, KEY, "build"); //$NON-NLS-1$

        assertEquals("plan", ChatProfileSelectorModel.synchronize(freshSession, configured)); //$NON-NLS-1$
        assertEquals("plan", freshSession.getAgentProfile()); //$NON-NLS-1$
    }

    private static final class DurableBackend {
        private final Map<String, String> durable = new HashMap<>();

        TestStore open() {
            return new TestStore(this);
        }
    }

    private static final class TestStore implements ChatProfilePreferencePersistence.Store {
        private final DurableBackend backend;
        private final Map<String, String> pending = new HashMap<>();
        private int putCount;
        private int flushCount;
        private BackingStoreException flushFailure;

        TestStore(DurableBackend backend) {
            this.backend = backend;
        }

        @Override
        public String get(String key, String defaultValue) {
            return pending.containsKey(key)
                    ? pending.get(key)
                    : backend.durable.getOrDefault(key, defaultValue);
        }

        @Override
        public void put(String key, String value) {
            putCount++;
            pending.put(key, value);
        }

        @Override
        public void flush() throws BackingStoreException {
            flushCount++;
            if (flushFailure != null) {
                throw flushFailure;
            }
            backend.durable.putAll(pending);
            pending.clear();
        }
    }
}
