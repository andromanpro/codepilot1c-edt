package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class ValidationTokenStoreBindingTest {

    @Test
    public void exactPayloadIsRequiredBeforeATokenCanBeConsumed() {
        ValidationTokenStore store = new ValidationTokenStore();
        Map<String, Object> approved = Map.of("project", "Demo", "name", "One"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String token = store.issueToken(ValidationOperation.CREATE_METADATA, "Demo", approved).token(); //$NON-NLS-1$

        MetadataOperationException failure = assertThrows(MetadataOperationException.class,
                () -> store.consumeToken(token, ValidationOperation.CREATE_METADATA, "Demo", //$NON-NLS-1$
                        Map.of("project", "Demo", "name", "Altered"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(MetadataOperationCode.INVALID_VALIDATION_TOKEN, failure.getCode());
        assertEquals(approved, store.consumeToken(token, ValidationOperation.CREATE_METADATA,
                "Demo", approved)); //$NON-NLS-1$
    }

    @Test
    public void consumedTokenCannotBeReplayedOrUsedForAnotherOperationOrProject() {
        ValidationTokenStore store = new ValidationTokenStore();
        Map<String, Object> payload = Map.of("project", "Demo", "name", "One"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String token = store.issueToken(ValidationOperation.CREATE_METADATA, "Demo", payload).token(); //$NON-NLS-1$

        assertThrows(MetadataOperationException.class,
                () -> store.consumeToken(token, ValidationOperation.UPDATE_METADATA, "Demo", payload)); //$NON-NLS-1$
        assertThrows(MetadataOperationException.class,
                () -> store.consumeToken(token, ValidationOperation.CREATE_METADATA, "Other", payload)); //$NON-NLS-1$
        assertEquals(payload, store.consumeToken(token, ValidationOperation.CREATE_METADATA, "Demo", payload)); //$NON-NLS-1$
        assertThrows(MetadataOperationException.class,
                () -> store.consumeToken(token, ValidationOperation.CREATE_METADATA, "Demo", payload)); //$NON-NLS-1$
    }

    @Test
    public void expiredTokenIsDeniedDeterministically() {
        AtomicLong now = new AtomicLong(1_000L);
        ValidationTokenStore store = new ValidationTokenStore(now::get);
        Map<String, Object> payload = Map.of("project", "Demo", "name", "One"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String token = store.issueToken(ValidationOperation.CREATE_METADATA, "Demo", payload).token(); //$NON-NLS-1$

        now.set(1_000L + 5L * 60L * 1000L + 1L);
        MetadataOperationException failure = assertThrows(MetadataOperationException.class,
                () -> store.consumeToken(token, ValidationOperation.CREATE_METADATA, "Demo", payload)); //$NON-NLS-1$

        assertEquals(MetadataOperationCode.INVALID_VALIDATION_TOKEN, failure.getCode());
    }

    /**
     * Deterministically interleaves two consumers inside the read/validate/remove
     * window of {@link ValidationTokenStore#consumeToken}: the first consumer is
     * parked by the injected clock right after it has read the entry, the second
     * consumer then runs to completion. Exactly one of them may claim the token.
     */
    @Test
    public void simultaneousConsumersClaimAOneTimeTokenExactlyOnce() throws Exception {
        Map<String, Object> payload = Map.of("project", "Demo", "name", "One"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        AtomicReference<Thread> parkedConsumer = new AtomicReference<>();
        AtomicInteger parkedConsumerClockCalls = new AtomicInteger();
        CountDownLatch reachedClaimWindow = new CountDownLatch(1);
        CountDownLatch otherConsumerFinished = new CountDownLatch(1);
        LongSupplier clock = () -> {
            // Call 1 is cleanupExpired(); call 2 is the expiry check, which runs
            // after the entry has already been read from the map.
            if (Thread.currentThread() == parkedConsumer.get()
                    && parkedConsumerClockCalls.incrementAndGet() == 2) {
                reachedClaimWindow.countDown();
                try {
                    otherConsumerFinished.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return 1_000L;
        };
        ValidationTokenStore store = new ValidationTokenStore(clock);
        String token = store.issueToken(ValidationOperation.CREATE_METADATA, "Demo", payload).token(); //$NON-NLS-1$

        AtomicReference<Object> parkedOutcome = new AtomicReference<>();
        Thread parked = new Thread(() -> {
            try {
                parkedOutcome.set(store.consumeToken(
                        token, ValidationOperation.CREATE_METADATA, "Demo", payload)); //$NON-NLS-1$
            } catch (RuntimeException e) {
                parkedOutcome.set(e);
            }
        }, "validation-token-parked-consumer"); //$NON-NLS-1$
        parkedConsumer.set(parked);
        parked.start();
        assertTrue(reachedClaimWindow.await(10, TimeUnit.SECONDS));

        Object racingOutcome;
        try {
            racingOutcome = store.consumeToken(
                    token, ValidationOperation.CREATE_METADATA, "Demo", payload); //$NON-NLS-1$
        } catch (RuntimeException e) {
            racingOutcome = e;
        }
        otherConsumerFinished.countDown();
        parked.join(10_000L);

        int successes = 0;
        for (Object outcome : new Object[] {parkedOutcome.get(), racingOutcome}) {
            if (outcome instanceof MetadataOperationException failure) {
                assertEquals(MetadataOperationCode.INVALID_VALIDATION_TOKEN, failure.getCode());
            } else {
                assertEquals(payload, outcome);
                successes++;
            }
        }
        assertEquals(1, successes);
    }
}
