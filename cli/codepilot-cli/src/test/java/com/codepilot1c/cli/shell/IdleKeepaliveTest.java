/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class IdleKeepaliveTest {
    @Test public void pingsAtFiveMinuteIdleAndNeverLeaksTasksAfterClose() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger pings = new AtomicInteger();
        IdleKeepalive keepalive = new IdleKeepalive(IdleKeepalive.DEFAULT_IDLE,
                pings::incrementAndGet, scheduler);
        keepalive.start();
        assertEquals(Duration.ofMinutes(5), scheduler.last.delay);
        keepalive.activity();
        assertTrue(scheduler.tasks.get(0).cancelled.get());
        scheduler.last.run();
        assertEquals(1, pings.get());
        assertEquals(3, scheduler.tasks.size());
        keepalive.close();
        assertTrue(scheduler.closed);
        assertTrue(scheduler.last.cancelled.get());
        scheduler.last.run();
        assertEquals(1, pings.get());
    }

    @Test public void busyTurnDefersPingUntilAnotherFullIdleWindow() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger pings = new AtomicInteger();
        IdleKeepalive keepalive = new IdleKeepalive(Duration.ofSeconds(7),
                pings::incrementAndGet, scheduler);
        keepalive.start();
        keepalive.busy(true);
        ManualTask duringTurn = scheduler.last;
        duringTurn.run();
        assertEquals(0, pings.get());
        keepalive.busy(false);
        assertTrue(scheduler.tasks.size() >= 3);
        scheduler.last.run();
        assertEquals(1, pings.get());
        keepalive.close();
    }

    private static final class ManualScheduler implements IdleKeepalive.Scheduler {
        private final List<ManualTask> tasks = new ArrayList<>();
        private ManualTask last;
        private boolean closed;
        @Override public IdleKeepalive.Cancellable schedule(Runnable task, Duration delay) {
            last = new ManualTask(task, delay);
            tasks.add(last);
            return last::cancel;
        }
        @Override public void close() { closed = true; }
    }

    private static final class ManualTask {
        private final Runnable task;
        private final Duration delay;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        ManualTask(Runnable task, Duration delay) { this.task = task; this.delay = delay; }
        void cancel() { cancelled.set(true); }
        void run() { if (!cancelled.get()) task.run(); }
    }
}
