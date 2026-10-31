/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

/** Cooperative cancellation boundary shared by providers and tools. */
public interface CancellationToken {
    boolean isCancelled();

    Registration onCancel(Runnable action);

    static CancellationToken none() {
        return NoneHolder.INSTANCE;
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    final class NoneHolder {
        private static final CancellationToken INSTANCE = new CancellationToken() {
            @Override public boolean isCancelled() { return false; }
            @Override public Registration onCancel(Runnable action) {
                java.util.Objects.requireNonNull(action, "action"); //$NON-NLS-1$
                return () -> { };
            }
        };
        private NoneHolder() { }
    }
}
