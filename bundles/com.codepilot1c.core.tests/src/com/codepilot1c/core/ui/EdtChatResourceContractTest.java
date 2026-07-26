/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class EdtChatResourceContractTest {

    @Test
    public void chatCssDefinesEdtCanvasAndReadableMessageLayout() throws Exception {
        String css = readRepoFile("bundles/com.codepilot1c.ui/resources/chat.css"); //$NON-NLS-1$

        assertTrue(css.contains("--chat-canvas-bg")); //$NON-NLS-1$
        assertTrue(css.contains("--message-max-width")); //$NON-NLS-1$
        assertTrue(css.contains("margin-left: auto")); //$NON-NLS-1$
        assertTrue(css.contains(".tool-call")); //$NON-NLS-1$
        assertTrue(css.contains(".tool-call-header")); //$NON-NLS-1$
        assertTrue(css.contains(".tool-call-status")); //$NON-NLS-1$
        assertTrue(css.contains(".tool-call-body")); //$NON-NLS-1$
        assertTrue(css.contains(".tool-call.expanded .tool-call-body")); //$NON-NLS-1$
        assertTrue(css.contains(".tool-calls-group")); //$NON-NLS-1$
        assertTrue(css.contains(".tool-call-result-preview")); //$NON-NLS-1$
        assertFalse(css.contains(".tool-call { display: none")); //$NON-NLS-1$
        assertFalse(css.contains(".tool-call { visibility: hidden")); //$NON-NLS-1$
        int systemRuleStart = css.indexOf(".message.system {"); //$NON-NLS-1$
        assertTrue(systemRuleStart >= 0);
        int systemRuleEnd = css.indexOf("}", systemRuleStart); //$NON-NLS-1$
        String systemRule = css.substring(systemRuleStart, systemRuleEnd);
        assertFalse(systemRule.contains("font-style")); //$NON-NLS-1$
    }

    @Test
    public void chatViewUsesStableComposerSizingHelpers() throws Exception {
        String source = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"); //$NON-NLS-1$

        assertTrue(source.contains("CHAT_INPUT_HEIGHT")); //$NON-NLS-1$
        assertTrue(source.contains("CHAT_ACTION_BUTTON_HEIGHT")); //$NON-NLS-1$
        assertTrue(source.contains("createChatActionButton")); //$NON-NLS-1$
    }

    @Test
    public void chatViewRequiresRussianAndReportsOutputLimitTruncation() throws Exception {
        String source = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"); //$NON-NLS-1$

        assertTrue(source.contains("ВСЕГДА отвечайте пользователю на русском языке")); //$NON-NLS-1$
        assertTrue(source.contains("OUTPUT_LIMIT_WARNING")); //$NON-NLS-1$
        assertTrue(source.contains("LlmResponse.FINISH_REASON_LENGTH")); //$NON-NLS-1$
        assertTrue(source.contains(".finishReason(finishReason)")); //$NON-NLS-1$
    }

    @Test
    public void codeMdInitializationUsesDedicatedServiceInsteadOfProgrammaticChatMessage() throws Exception {
        String source = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"); //$NON-NLS-1$
        String method = extractMethod(source, "private void runCodeMdInitialization()"); //$NON-NLS-1$

        assertTrue(method.contains("PROJECT_MEMORY_INIT_SERVICE.initialize")); //$NON-NLS-1$
        assertFalse(method.contains("sendProgrammaticMessage(")); //$NON-NLS-1$
    }

    @Test
    public void chatJsShowsSuccessfulToolOutputPreview() throws Exception {
        String js = readRepoFile("bundles/com.codepilot1c.ui/resources/chat.js"); //$NON-NLS-1$

        assertTrue(js.contains("status === 'success'")); //$NON-NLS-1$
        assertTrue(js.contains("tool-call-result-preview")); //$NON-NLS-1$
        assertFalse(js.contains("avoid inline stdout-style dumps")); //$NON-NLS-1$
    }

    @Test
    public void toolResultCardsExposeCompleteScrollableOutput() throws Exception {
        String css = readRepoFile("bundles/com.codepilot1c.ui/resources/chat.css"); //$NON-NLS-1$
        String chatView = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"); //$NON-NLS-1$
        String updateMethod = extractMethod(chatView, "private void updateToolCallCardWithResult("); //$NON-NLS-1$
        String fallbackMethod = extractMethod(chatView, "private void appendToolResultMessage("); //$NON-NLS-1$

        int resultRuleStart = css.indexOf(".tool-call-result-preview {"); //$NON-NLS-1$
        assertTrue(resultRuleStart >= 0);
        int resultRuleEnd = css.indexOf("}", resultRuleStart); //$NON-NLS-1$
        String resultRule = css.substring(resultRuleStart, resultRuleEnd);
        assertTrue(resultRule.contains("overflow: auto")); //$NON-NLS-1$
        assertFalse(resultRule.contains("overflow: hidden")); //$NON-NLS-1$

        assertFalse(chatView.contains("MAX_TOOL_RESULT_PREVIEW_CHARS")); //$NON-NLS-1$
        assertFalse(updateMethod.contains("substring(0")); //$NON-NLS-1$
        assertFalse(updateMethod.contains("обрезано в UI")); //$NON-NLS-1$
        assertFalse(fallbackMethod.contains("substring(0")); //$NON-NLS-1$
        assertFalse(fallbackMethod.contains("обрезано")); //$NON-NLS-1$
    }

    @Test
    public void toolCallCardsAttachToCurrentAssistantMessage() throws Exception {
        String source = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/BrowserChatPanel.java"); //$NON-NLS-1$
        String method = extractMethod(source,
                "private String buildInsertToolCallsScript(String escapedOwnerMessageId, String escapedHtml)"); //$NON-NLS-1$
        String css = readRepoFile("bundles/com.codepilot1c.ui/resources/chat.css"); //$NON-NLS-1$

        assertTrue(method.contains("document.getElementById(ownerId)")); //$NON-NLS-1$
        assertTrue(method.contains("owner.querySelector('.message-content')")); //$NON-NLS-1$
        assertTrue(method.contains("target.insertAdjacentHTML('beforeend'")); //$NON-NLS-1$
        assertTrue(method.contains("insertMessageFlowHtml")); //$NON-NLS-1$
        assertTrue(method.contains("ensureTypingIndicatorAtBottom")); //$NON-NLS-1$
        assertTrue(css.contains(".message .tool-call")); //$NON-NLS-1$
        assertTrue(css.contains(".message .tool-calls-group")); //$NON-NLS-1$
    }

    @Test
    public void typingIndicatorStaysInScrollableMessageFlow() throws Exception {
        String source = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/BrowserChatPanel.java"); //$NON-NLS-1$
        String buildHtmlDocument = extractMethod(source, "private String buildHtmlDocument(String messagesHtml)"); //$NON-NLS-1$
        String css = readRepoFile("bundles/com.codepilot1c.ui/resources/chat.css"); //$NON-NLS-1$

        int messagesContainer = buildHtmlDocument.indexOf("message-container\\\" id=\\\"messages"); //$NON-NLS-1$
        int typingIndicator = buildHtmlDocument.indexOf("typing-indicator\\\" id=\\\"typing-indicator"); //$NON-NLS-1$
        int firstMessageContainerClose = buildHtmlDocument.indexOf("\"        </div>\\n\" +", messagesContainer); //$NON-NLS-1$
        int tokenFooter = buildHtmlDocument.indexOf("id=\\\"token-footer"); //$NON-NLS-1$
        assertTrue(messagesContainer >= 0);
        assertTrue(typingIndicator > messagesContainer);
        assertTrue(firstMessageContainerClose > typingIndicator);
        assertTrue(tokenFooter > typingIndicator);
        assertFalse(css.contains("width: min(100% - 48px, var(--message-max-width));")); //$NON-NLS-1$
        assertTrue(css.contains("width: min(100%, var(--message-max-width));")); //$NON-NLS-1$
    }

    @Test
    public void incrementalMessageInsertPreservesTypingIndicatorAsLastFlowElement() throws Exception {
        String source = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/BrowserChatPanel.java"); //$NON-NLS-1$
        String js = readRepoFile("bundles/com.codepilot1c.ui/resources/chat.js"); //$NON-NLS-1$
        String addMessage = extractMethod(source,
                "public void addMessage(String sender, String content, boolean isAssistant, boolean isSystem,\n" //$NON-NLS-1$
                        + "            List<LlmAttachment> attachments, String modelName)"); //$NON-NLS-1$
        String updateLastMessage = extractMethod(source, "public void updateLastMessage(String content)"); //$NON-NLS-1$

        assertTrue(js.contains("function insertMessageFlowHtml(html)")); //$NON-NLS-1$
        assertTrue(js.contains("typing.insertAdjacentHTML('beforebegin', html)")); //$NON-NLS-1$
        assertTrue(addMessage.contains("insertMessageFlowHtml")); //$NON-NLS-1$
        assertFalse(updateLastMessage.contains(".message:last-child")); //$NON-NLS-1$
        assertTrue(updateLastMessage.contains("updateMessageWithReasoning")); //$NON-NLS-1$
        assertTrue(js.contains("document.querySelectorAll('.message.assistant')")); //$NON-NLS-1$
    }

    @Test
    public void toolExecutionStageHidesDuplicateTypingIndicator() throws Exception {
        String source = readRepoFile("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/BrowserChatPanel.java"); //$NON-NLS-1$
        String setProcessingStage = extractMethod(source, "public void setProcessingStage(String stage)"); //$NON-NLS-1$

        assertTrue(setProcessingStage.contains("isToolExecutionStage(stage)")); //$NON-NLS-1$
        assertTrue(setProcessingStage.contains("indicator.style.display")); //$NON-NLS-1$
        assertTrue(source.contains("stage.startsWith(\"Выполнение:\")")); //$NON-NLS-1$
    }

    private static String readRepoFile(String relativePath) throws IOException {
        return Files.readString(repoRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static String extractMethod(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("Method not found: " + signature); //$NON-NLS-1$
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("Method end not found: " + signature); //$NON-NLS-1$
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath(); //$NON-NLS-1$
        while (current != null) {
            if (Files.exists(current.resolve("bundles/com.codepilot1c.ui/resources/chat.css"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found"); //$NON-NLS-1$
    }
}
