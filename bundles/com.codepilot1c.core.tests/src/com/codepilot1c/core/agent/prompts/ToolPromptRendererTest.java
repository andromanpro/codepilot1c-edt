package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.ToolDefinition;

public class ToolPromptRendererTest {

    @Test
    public void applyToMessagesAddsVisibleToolsToSystemPrompt() {
        List<LlmMessage> messages = ToolPromptRenderer.applyToMessages(
                List.of(LlmMessage.system("BASE"), LlmMessage.user("task")), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(tool("read_file"), tool("grep"))); //$NON-NLS-1$ //$NON-NLS-2$

        String systemPrompt = messages.get(0).getContent();

        assertTrue(systemPrompt.contains("BASE")); //$NON-NLS-1$
        assertTrue(systemPrompt.contains("Runtime Tool Surface")); //$NON-NLS-1$
        assertTrue(systemPrompt.contains("`read_file`")); //$NON-NLS-1$
        assertTrue(systemPrompt.contains("`grep`")); //$NON-NLS-1$
    }

    @Test
    public void applyToMessagesReplacesPreviousToolSurfaceSection() {
        List<LlmMessage> first = ToolPromptRenderer.applyToMessages(
                List.of(LlmMessage.system("BASE"), LlmMessage.user("task")), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(tool("read_file"))); //$NON-NLS-1$
        List<LlmMessage> second = ToolPromptRenderer.applyToMessages(first, List.of(tool("edit_file"))); //$NON-NLS-1$

        String systemPrompt = second.get(0).getContent();

        assertTrue(systemPrompt.contains("BASE")); //$NON-NLS-1$
        assertTrue(systemPrompt.contains("`edit_file`")); //$NON-NLS-1$
        assertFalse(systemPrompt.contains("`read_file`")); //$NON-NLS-1$
    }

    @Test
    public void applyToMessagesLeavesHistoryWithoutSystemMessageUnchanged() {
        List<LlmMessage> messages = ToolPromptRenderer.applyToMessages(
                List.of(LlmMessage.user("task")), //$NON-NLS-1$
                List.of(tool("read_file"))); //$NON-NLS-1$

        assertFalse(messages.get(0).getContent().contains("Runtime Tool Surface")); //$NON-NLS-1$
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, "Description for " + name, "{\"type\":\"object\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
