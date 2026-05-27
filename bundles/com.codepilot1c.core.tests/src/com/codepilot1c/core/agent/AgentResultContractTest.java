package com.codepilot1c.core.agent;

import static org.junit.Assert.assertFalse;

import java.util.Collections;

import org.junit.Test;

public class AgentResultContractTest {

    @Test
    public void completedStateWithErrorMessageIsNotSuccess() {
        AgentResult result = AgentResult.builder()
                .finalState(AgentState.COMPLETED)
                .errorMessage("Достигнут лимит шагов: 100") //$NON-NLS-1$
                .conversationHistory(Collections.emptyList())
                .stepsExecuted(100)
                .toolCallsExecuted(100)
                .executionTimeMs(1000)
                .build();

        assertFalse(result.isSuccess());
    }
}
