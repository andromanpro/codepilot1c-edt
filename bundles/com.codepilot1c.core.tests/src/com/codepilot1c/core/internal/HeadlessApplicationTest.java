package com.codepilot1c.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

public class HeadlessApplicationTest {

    @Test
    public void startSetsSignalBlocksAndStopsThroughInjectedCoordinator() throws Exception {
        clearSignalProperties();
        TestCoordinator coordinator = new TestCoordinator();
        HeadlessApplication application = new HeadlessApplication(coordinator);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread applicationThread = new Thread(() -> {
            try {
                result.set(application.start((IApplicationContext) null));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        applicationThread.start();

        assertTrue(coordinator.awaitEntered.await(2, TimeUnit.SECONDS));
        assertTrue(Boolean.parseBoolean(System.getProperty(HeadlessModeSignal.HEADLESS_PROPERTY)));
        assertEquals(HeadlessModeSignal.APPLICATION_ID,
                System.getProperty(HeadlessModeSignal.ECLIPSE_APPLICATION_PROPERTY));
        assertTrue(applicationThread.isAlive());

        application.stop();
        applicationThread.join(2000L);

        assertFalse(applicationThread.isAlive());
        assertNull(failure.get());
        assertEquals(IApplication.EXIT_OK, result.get());
        assertEquals(1, coordinator.startCount);
        assertEquals(1, coordinator.stopCount);
        assertNull(System.getProperty(HeadlessModeSignal.HEADLESS_PROPERTY));
        assertNull(System.getProperty(HeadlessModeSignal.ECLIPSE_APPLICATION_PROPERTY));
    }

    @Test
    public void startFailureStillReleasesCoordinatorAndRestoresSignal() throws Exception {
        String previousHeadless = System.getProperty(HeadlessModeSignal.HEADLESS_PROPERTY);
        String previousApplication = System.getProperty(HeadlessModeSignal.ECLIPSE_APPLICATION_PROPERTY);
        try {
            System.setProperty(HeadlessModeSignal.HEADLESS_PROPERTY, "false"); //$NON-NLS-1$
            System.setProperty(HeadlessModeSignal.ECLIPSE_APPLICATION_PROPERTY, "other.application"); //$NON-NLS-1$
            TestCoordinator coordinator = new TestCoordinator();
            coordinator.failure = new IllegalStateException("synthetic start failure"); //$NON-NLS-1$

            try {
                new HeadlessApplication(coordinator).start(null);
            } catch (IllegalStateException expected) {
                // expected
            }

            assertEquals(1, coordinator.startCount);
            assertEquals(1, coordinator.stopCount);
            assertEquals("false", System.getProperty(HeadlessModeSignal.HEADLESS_PROPERTY)); //$NON-NLS-1$
            assertEquals("other.application",
                    System.getProperty(HeadlessModeSignal.ECLIPSE_APPLICATION_PROPERTY)); //$NON-NLS-1$
        } finally {
            restoreProperty(HeadlessModeSignal.HEADLESS_PROPERTY, previousHeadless);
            restoreProperty(HeadlessModeSignal.ECLIPSE_APPLICATION_PROPERTY, previousApplication);
        }
    }

    @Test
    public void productionCoordinatorMakesHostStartupFailureReadable() {
        CoreHeadlessApplicationCoordinator coordinator = new CoreHeadlessApplicationCoordinator(
                new CountDownLatch(1),
                () -> { },
                () -> {
                    throw new IllegalStateException("bind failed"); //$NON-NLS-1$
                });

        try {
            coordinator.start();
        } catch (IllegalStateException e) {
            assertEquals("Failed to start headless MCP host", e.getMessage()); //$NON-NLS-1$
            assertEquals("bind failed", e.getCause().getMessage()); //$NON-NLS-1$
            coordinator.stop();
            return;
        }
        throw new AssertionError("Expected headless host startup failure"); //$NON-NLS-1$
    }

    @Test
    public void productionCoordinatorRunsEdtBootstrapBeforeStartingTheHost() {
        List<String> order = new ArrayList<>();
        CoreHeadlessApplicationCoordinator coordinator = new CoreHeadlessApplicationCoordinator(
                new CountDownLatch(1),
                () -> order.add("bootstrap"), //$NON-NLS-1$
                () -> {
                    order.add("host"); //$NON-NLS-1$
                    throw new IllegalStateException("stop after recording order"); //$NON-NLS-1$
                });

        try {
            coordinator.start();
        } catch (IllegalStateException expected) {
            // expected: the host starter test double always throws once it records its entry
        }

        assertEquals(List.of("bootstrap", "host"), order); //$NON-NLS-1$ //$NON-NLS-2$
        coordinator.stop();
    }

    @Test
    public void productionCoordinatorNeverStartsTheHostWhenEdtBootstrapFails() {
        AtomicBoolean hostStarted = new AtomicBoolean();
        CoreHeadlessApplicationCoordinator coordinator = new CoreHeadlessApplicationCoordinator(
                new CountDownLatch(1),
                () -> {
                    throw new IllegalStateException("IConfigurationProvider is unavailable in EDT runtime"); //$NON-NLS-1$
                },
                () -> {
                    hostStarted.set(true);
                    return null;
                });

        try {
            coordinator.start();
        } catch (IllegalStateException e) {
            assertEquals("Failed to bootstrap EDT runtime services", e.getMessage()); //$NON-NLS-1$
            assertEquals("IConfigurationProvider is unavailable in EDT runtime", e.getCause().getMessage()); //$NON-NLS-1$
            assertFalse(hostStarted.get());
            coordinator.stop();
            return;
        }
        throw new AssertionError("Expected EDT bootstrap failure"); //$NON-NLS-1$
    }

    private static void clearSignalProperties() {
        System.clearProperty(HeadlessModeSignal.HEADLESS_PROPERTY);
        System.clearProperty(HeadlessModeSignal.ECLIPSE_APPLICATION_PROPERTY);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class TestCoordinator implements HeadlessApplicationCoordinator {

        private final CountDownLatch awaitEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean stopped = new AtomicBoolean();
        private int startCount;
        private int stopCount;
        private RuntimeException failure;

        @Override
        public void start() {
            startCount++;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void awaitStop() throws InterruptedException {
            awaitEntered.countDown();
            release.await(2, TimeUnit.SECONDS);
        }

        @Override
        public void stop() {
            if (stopped.compareAndSet(false, true)) {
                stopCount++;
                release.countDown();
            }
        }
    }
}
