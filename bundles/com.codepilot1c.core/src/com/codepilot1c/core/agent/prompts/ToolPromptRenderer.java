/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.util.ArrayList;
import java.util.List;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.ToolDefinition;

/**
 * Renders the runtime tool surface into the system prompt.
 */
public final class ToolPromptRenderer {

    static final String START_MARKER = "<!-- codepilot-tool-surface:start -->"; //$NON-NLS-1$
    static final String END_MARKER = "<!-- codepilot-tool-surface:end -->"; //$NON-NLS-1$
    private static final int DESCRIPTION_LIMIT = 360;

    private ToolPromptRenderer() {
    }

    public static String render(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append(START_MARKER).append('\n');
        prompt.append("## Runtime Tool Surface\n\n"); //$NON-NLS-1$
        prompt.append("The tool definitions in this request are the source of truth. "); //$NON-NLS-1$
        prompt.append("Use only tools listed here; if a tool is absent, it is unavailable in this turn.\n\n"); //$NON-NLS-1$
        for (ToolDefinition tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                continue;
            }
            prompt.append("- `").append(tool.getName()).append('`'); //$NON-NLS-1$
            String description = compact(tool.getDescription());
            if (!description.isBlank()) {
                prompt.append(": ").append(description); //$NON-NLS-1$
            }
            prompt.append('\n');
        }
        prompt.append(END_MARKER);
        return prompt.toString();
    }

    public static List<LlmMessage> applyToMessages(List<LlmMessage> messages, List<ToolDefinition> tools) {
        if (messages == null || messages.isEmpty()) {
            return messages != null ? List.copyOf(messages) : List.of();
        }
        String rendered = render(tools);
        if (rendered.isBlank()) {
            return List.copyOf(messages);
        }

        List<LlmMessage> result = new ArrayList<>(messages);
        LlmMessage first = result.get(0);
        if (first.getRole() != LlmMessage.Role.SYSTEM) {
            return List.copyOf(result);
        }

        String base = removeExistingSection(first.getContent()).strip();
        String content = base.isBlank() ? rendered : base + "\n\n" + rendered; //$NON-NLS-1$
        result.set(0, LlmMessage.system(content));
        return List.copyOf(result);
    }

    private static String removeExistingSection(String content) {
        if (content == null || content.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        int start = content.indexOf(START_MARKER);
        int end = content.indexOf(END_MARKER);
        if (start < 0 || end < start) {
            return content;
        }
        int endInclusive = end + END_MARKER.length();
        return (content.substring(0, start) + content.substring(endInclusive)).strip();
    }

    private static String compact(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String compact = value.replace('\n', ' ').replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
        if (compact.length() <= DESCRIPTION_LIMIT) {
            return compact;
        }
        return compact.substring(0, DESCRIPTION_LIMIT - 1).strip() + "..."; //$NON-NLS-1$
    }
}
