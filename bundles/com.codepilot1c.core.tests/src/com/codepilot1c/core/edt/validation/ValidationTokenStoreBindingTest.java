package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
}
