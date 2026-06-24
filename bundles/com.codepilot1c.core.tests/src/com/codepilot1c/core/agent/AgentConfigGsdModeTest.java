package com.codepilot1c.core.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AgentConfigGsdModeTest {

    @Test
    public void gsdModeDefaultsOff() {
        assertFalse(AgentConfig.defaults().isGsdMode());
        assertFalse(AgentConfig.builder().build().isGsdMode());
    }

    @Test
    public void builderEnablesGsdMode() {
        assertTrue(AgentConfig.builder().gsdMode(true).build().isGsdMode());
    }

    @Test
    public void fromCopiesGsdMode() {
        AgentConfig base = AgentConfig.builder().gsdMode(true).build();
        AgentConfig copy = AgentConfig.builder().from(base).build();
        assertTrue(copy.isGsdMode());

        AgentConfig offCopy = AgentConfig.builder().from(AgentConfig.defaults()).build();
        assertFalse(offCopy.isGsdMode());
    }
}
