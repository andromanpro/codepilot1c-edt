/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.codepilot1c.runtime.agent.CancellationToken;

/**
 * Serializes every shell input operation and makes cancellation a terminal
 * ownership hand-off: abort the active read, then wait until its owner leaves.
 */
final class TerminalReader {
    private final ShellTerminal terminal;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition idle = lock.newCondition();
    private Thread owner;

    TerminalReader(ShellTerminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    String readLine(String prompt) {
        return readLine(prompt, null);
    }

    String readLine(String prompt, CancellationToken cancellation) {
        lock.lock();
        CancellationToken.Registration registration = null;
        try {
            while (owner != null) idle.awaitUninterruptibly();
            if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
            owner = Thread.currentThread();
            if (cancellation != null) registration = cancellation.onCancel(this::abortAndAwait);
        } finally {
            lock.unlock();
        }
        try {
            if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
            String value = terminal.readLine(prompt);
            if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
            return value;
        } finally {
            if (registration != null) registration.close();
            release();
        }
    }

    void abortAndAwait() {
        lock.lock();
        try {
            Thread visibleOwner = owner;
            if (visibleOwner == null) return;
            if (visibleOwner == Thread.currentThread()) return;
            RuntimeException abortFailure = null;
            try {
                terminal.abortRead();
            } catch (RuntimeException failure) {
                abortFailure = failure;
            }
            while (owner == visibleOwner) idle.awaitUninterruptibly();
            if (abortFailure != null) throw abortFailure;
        } finally {
            lock.unlock();
        }
    }

    void awaitIdle() {
        lock.lock();
        try {
            while (owner != null) idle.awaitUninterruptibly();
        } finally {
            lock.unlock();
        }
    }

    private void release() {
        lock.lock();
        try {
            if (owner == Thread.currentThread()) {
                owner = null;
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }
}
