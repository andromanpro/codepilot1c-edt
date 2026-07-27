package com.codepilot1c.core.agent.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StreamChunkEventTest {

    @Test
    public void partialIsContentNotReasoning() {
        StreamChunkEvent e = StreamChunkEvent.partial(1, "ответ"); //$NON-NLS-1$
        assertEquals("ответ", e.getContent()); //$NON-NLS-1$
        assertFalse(e.isReasoning());
        assertFalse(e.isComplete());
    }

    @Test
    public void partialReasoningIsFlagged() {
        StreamChunkEvent e = StreamChunkEvent.partialReasoning(2, "думаю..."); //$NON-NLS-1$
        assertEquals("думаю...", e.getContent()); //$NON-NLS-1$
        assertTrue(e.isReasoning());
        assertFalse(e.isComplete());
    }

    @Test
    public void completeIsNotReasoning() {
        StreamChunkEvent e = StreamChunkEvent.complete(3, "stop"); //$NON-NLS-1$
        assertTrue(e.isComplete());
        assertFalse(e.isReasoning());
        assertEquals("stop", e.getFinishReason()); //$NON-NLS-1$
    }

    @Test
    public void legacyConstructorDefaultsReasoningFalse() {
        StreamChunkEvent e = new StreamChunkEvent(1, "x", false, null); //$NON-NLS-1$
        assertFalse(e.isReasoning());
    }
}
