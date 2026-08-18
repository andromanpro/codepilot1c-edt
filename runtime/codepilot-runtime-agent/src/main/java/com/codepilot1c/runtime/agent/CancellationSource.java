/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe host-controlled cancellation source. */
public final class CancellationSource implements CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> actions = new CopyOnWriteArrayList<>();

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public Registration onCancel(Runnable action) {
        Objects.requireNonNull(action, "action"); //$NON-NLS-1$
        if (cancelled.get()) {
            runSafely(action);
            return () -> { };
        }
        actions.add(action);
        if (cancelled.get() && actions.remove(action)) runSafely(action);
        return () -> actions.remove(action);
    }

    /** Cancels once and invokes all registered callbacks. */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        for (Runnable action : actions) runSafely(action);
        actions.clear();
        return true;
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Cancellation must remain best effort and must not expose callback failures.
        }
    }
}
