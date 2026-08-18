/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-task five-minute idle MCP ping scheduler. */
public final class IdleKeepalive implements AutoCloseable {
    public static final Duration DEFAULT_IDLE = Duration.ofMinutes(5);
    private final Duration idle;
    private final Ping ping;
    private final Scheduler scheduler;
    private final Object lock = new Object();
    private Cancellable scheduled;
    private boolean closed;
    private boolean busy;

    public IdleKeepalive(Ping ping) {
        this(DEFAULT_IDLE, ping, new ExecutorScheduler());
    }

    public IdleKeepalive(Duration idle, Ping ping, Scheduler scheduler) {
        this.idle = Objects.requireNonNull(idle, "idle");
        if (idle.isZero() || idle.isNegative()) throw new IllegalArgumentException("idle must be positive");
        this.ping = Objects.requireNonNull(ping, "ping");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void start() { activity(); }

    public void activity() {
        synchronized (lock) {
            if (closed) return;
            if (scheduled != null) scheduled.cancel();
            scheduled = scheduler.schedule(this::fire, idle);
        }
    }

    public void busy(boolean value) {
        synchronized (lock) { busy = value; }
        if (!value) activity();
    }

    private void fire() {
        synchronized (lock) {
            scheduled = null;
            if (closed) return;
            if (busy) {
                scheduled = scheduler.schedule(this::fire, idle);
                return;
            }
        }
        try { ping.run(); }
        catch (Exception ignored) { /* A foreground refresh reports actionable MCP failures. */ }
        activity();
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (scheduled != null) scheduled.cancel();
            scheduled = null;
        }
        scheduler.close();
    }

    @FunctionalInterface public interface Ping { void run() throws Exception; }
    public interface Scheduler extends AutoCloseable {
        Cancellable schedule(Runnable task, Duration delay);
        @Override void close();
    }
    @FunctionalInterface public interface Cancellable { void cancel(); }

    private static final class ExecutorScheduler implements Scheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "codepilot-shell-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        @Override public Cancellable schedule(Runnable task, Duration delay) {
            ScheduledFuture<?> future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        }
        @Override public void close() { executor.shutdownNow(); }
    }
}
