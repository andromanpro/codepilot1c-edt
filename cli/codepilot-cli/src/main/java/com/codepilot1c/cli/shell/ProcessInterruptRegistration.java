/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort JVM SIGINT registration that restores the previous handler on close. */
public final class ProcessInterruptRegistration implements AutoCloseable {
    private final Object signal;
    private final Object previous;
    private final Method handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ProcessInterruptRegistration(Object signal, Object previous, Method handle) {
        this.signal = signal;
        this.previous = previous;
        this.handle = handle;
    }

    public static ProcessInterruptRegistration install(Runnable interrupt) {
        Objects.requireNonNull(interrupt, "interrupt");
        try {
            Class<?> signalType = Class.forName("sun.misc.Signal");
            Class<?> handlerType = Class.forName("sun.misc.SignalHandler");
            Object signal = signalType.getConstructor(String.class).newInstance("INT");
            Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(),
                    new Class<?>[] { handlerType }, (proxy, method, arguments) -> {
                        if ("handle".equals(method.getName())) interrupt.run();
                        return null;
                    });
            Method handle = signalType.getMethod("handle", signalType, handlerType);
            Object previous = handle.invoke(null, signal, handler);
            return new ProcessInterruptRegistration(signal, previous, handle);
        } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) {
            return new ProcessInterruptRegistration(null, null, null);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true) || signal == null) return;
        try { handle.invoke(null, signal, previous); }
        catch (ReflectiveOperationException | RuntimeException ignored) {
            // Process teardown retains the platform's current handler if restoration is unavailable.
        }
    }
}
