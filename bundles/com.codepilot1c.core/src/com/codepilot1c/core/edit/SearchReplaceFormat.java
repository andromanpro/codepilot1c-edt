/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Parser for SEARCH/REPLACE edit format.
 *
 * <p>Parses edit blocks in the Aider-style format:</p>
 * <pre>
 * <<<<<<< SEARCH
 * [original code]
 * =======
 * [replacement code]
 * >>>>>>> REPLACE
 * </pre>
 *
 * <p>Supports multiple blocks in a single response and tolerates
 * markdown code fences around the blocks.</p>
 */
public class SearchReplaceFormat {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SearchReplaceFormat.class);

    /**
     * Pattern for SEARCH/REPLACE blocks.
     * Captures: (1) search text, (2) replace text
     */
    private static final Pattern SEARCH_REPLACE_PATTERN = Pattern.compile(
            "<<<<<<<?\\s*SEARCH\\s*\\n" +     // Start marker
            "(.*?)" +                           // Group 1: search text (non-greedy)
            "\\n?=======\\n?" +                 // Separator
            "(.*?)" +                           // Group 2: replace text (non-greedy)
            "\\n?>>>>>>?>?\\s*REPLACE",         // End marker
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern for removing markdown code fence around blocks.
     * Supports any language specifier (diff, patch, java, text, etc.)
     */
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "```[a-zA-Z0-9]*\\s*\\n?" +         // Opening fence with optional language
            "(.*?)" +                            // Content
            "\\n?```",                           // Closing fence
            Pattern.DOTALL
    );

    /**
     * Parses edit blocks from LLM response.
     *
     * @param llmResponse the raw LLM response text
     * @return list of parsed edit blocks, empty if none found
     */
    public List<EditBlock> parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isEmpty()) {
            return Collections.emptyList();
        }

        // Normalize line endings to \n for consistent matching
        String normalized = llmResponse.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        // Remove code fences if present
        String content = unwrapCodeFences(normalized);

        List<EditBlock> blocks = new ArrayList<>();
        Matcher matcher = SEARCH_REPLACE_PATTERN.matcher(content);

        int blockIndex = 0;
        while (matcher.find()) {
            String searchText = matcher.group(1);
            String replaceText = matcher.group(2);

            // Normalize the texts
            searchText = normalizeBlockContent(searchText);
            replaceText = normalizeBlockContent(replaceText);

            blocks.add(new EditBlock(searchText, replaceText, blockIndex++));
            LOG.debug("Parsed EditBlock %d: search=%d chars, replace=%d chars", //$NON-NLS-1$
                    blockIndex - 1, searchText.length(), replaceText.length());
        }

        LOG.info("SearchReplaceFormat: parsed %d blocks from response", blocks.size()); //$NON-NLS-1$
        return blocks;
    }

    /**
     * Checks if the response contains SEARCH/REPLACE blocks.
     *
     * @param llmResponse the raw LLM response text
     * @return true if blocks are present
     */
    public boolean hasBlocks(String llmResponse) {
        if (llmResponse == null || llmResponse.isEmpty()) {
            return false;
        }
        String content = unwrapCodeFences(llmResponse);
        return SEARCH_REPLACE_PATTERN.matcher(content).find();
    }

    /**
     * Extracts the file path from the response if specified.
     *
     * <p>Looks for patterns like:
     * <ul>
     *   <li>{@code path/to/file.java}</li>
     *   <li>{@code File: path/to/file.java}</li>
     *   <li>{@code # path/to/file.java}</li>
     * </ul>
     *
     * @param llmResponse the raw LLM response
     * @return the file path, or null if not found
     */
    public String extractFilePath(String llmResponse) {
        if (llmResponse == null || llmResponse.isEmpty()) {
            return null;
        }

        // Pattern for file path header
        Pattern filePathPattern = Pattern.compile(
                "(?:^|\\n)(?:#\\s*|File:\\s*|path:\\s*)?([\\w/.\\-]+\\.[a-zA-Z]+)\\s*(?:\\n|$)", //$NON-NLS-1$
                Pattern.MULTILINE
        );

        Matcher matcher = filePathPattern.matcher(llmResponse);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Validates edit blocks for common issues.
     *
     * @param blocks the blocks to validate
     * @return list of validation errors, empty if valid
     */
    public List<String> validate(List<EditBlock> blocks) {
        List<String> errors = new ArrayList<>();

        if (blocks.isEmpty()) {
            errors.add("Не найдено SEARCH/REPLACE блоков"); //$NON-NLS-1$
            return errors;
        }

        for (int i = 0; i < blocks.size(); i++) {
            EditBlock block = blocks.get(i);

            // Check for empty search in non-insertion context
            if (block.getSearchText().isEmpty() && !block.isInsertion()) {
                errors.add(String.format("Блок %d: пустой SEARCH текст", i + 1)); //$NON-NLS-1$
            }

            // Check for very short search text (likely to match multiple places)
            if (block.getSearchText().length() > 0 && block.getSearchText().length() < 10) {
                // Warning, not error
                LOG.warn("Блок %d: короткий SEARCH текст (%d символов) может совпасть в нескольких местах", //$NON-NLS-1$
                        i + 1, block.getSearchText().length());
            }

            // Check for common formatting issues
            if (block.getSearchText().contains("<<<<<<") || block.getSearchText().contains(">>>>>>>")) { //$NON-NLS-1$ //$NON-NLS-2$
                errors.add(String.format("Блок %d: SEARCH текст содержит маркеры блоков", i + 1)); //$NON-NLS-1$
            }
            if (block.getReplaceText().contains("<<<<<<") || block.getReplaceText().contains(">>>>>>>")) { //$NON-NLS-1$ //$NON-NLS-2$
                errors.add(String.format("Блок %d: REPLACE текст содержит маркеры блоков", i + 1)); //$NON-NLS-1$
            }

            // A stray "=======" separator line inside a parsed block means the block was
            // malformed (e.g. an extra separator): the non-greedy parser swallows everything
            // up to the end marker, so the literal separator line would be written into the
            // file as garbage. Reject instead of silently corrupting the target file.
            if (containsSeparatorLine(block.getSearchText())) {
                errors.add(String.format(
                        "Блок %d: SEARCH текст содержит строку-разделитель '=======' - вероятно, лишний разделитель в блоке", i + 1)); //$NON-NLS-1$
            }
            if (containsSeparatorLine(block.getReplaceText())) {
                errors.add(String.format(
                        "Блок %d: REPLACE текст содержит строку-разделитель '=======' - вероятно, лишний разделитель в блоке", i + 1)); //$NON-NLS-1$
            }
        }

        return errors;
    }

    /**
     * Checks whether the content contains a line consisting solely of a
     * {@code =======} separator (7+ equals signs, optional surrounding whitespace).
     *
     * @param content the block content to check
     * @return true if a stray separator line is present
     */
    private static boolean containsSeparatorLine(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        return content.lines().anyMatch(line -> line.strip().matches("={7,}")); //$NON-NLS-1$
    }

    /**
     * Formats edit blocks back to string representation.
     *
     * @param blocks the blocks to format
     * @return formatted string
     */
    public String format(List<EditBlock> blocks) {
        StringBuilder sb = new StringBuilder();

        for (EditBlock block : blocks) {
            if (sb.length() > 0) {
                sb.append("\n\n"); //$NON-NLS-1$
            }
            sb.append("<<<<<<< SEARCH\n"); //$NON-NLS-1$
            sb.append(block.getSearchText());
            if (!block.getSearchText().endsWith("\n")) { //$NON-NLS-1$
                sb.append("\n"); //$NON-NLS-1$
            }
            sb.append("=======\n"); //$NON-NLS-1$
            sb.append(block.getReplaceText());
            if (!block.getReplaceText().endsWith("\n")) { //$NON-NLS-1$
                sb.append("\n"); //$NON-NLS-1$
            }
            sb.append(">>>>>>> REPLACE"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    /**
     * Creates a single edit block programmatically.
     *
     * @param oldText the text to replace
     * @param newText the replacement text
     * @return formatted SEARCH/REPLACE block
     */
    public static String createBlock(String oldText, String newText) {
        StringBuilder sb = new StringBuilder();
        sb.append("<<<<<<< SEARCH\n"); //$NON-NLS-1$
        sb.append(oldText);
        if (!oldText.endsWith("\n")) { //$NON-NLS-1$
            sb.append("\n"); //$NON-NLS-1$
        }
        sb.append("=======\n"); //$NON-NLS-1$
        sb.append(newText);
        if (!newText.endsWith("\n")) { //$NON-NLS-1$
            sb.append("\n"); //$NON-NLS-1$
        }
        sb.append(">>>>>>> REPLACE"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Removes markdown code fences from content.
     */
    private String unwrapCodeFences(String content) {
        // First, try to extract content from code fences
        Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (fenceMatcher.find()) {
            // Add content before the fence
            result.append(content.substring(lastEnd, fenceMatcher.start()));
            // Add content inside the fence (unwrapped)
            result.append(fenceMatcher.group(1));
            lastEnd = fenceMatcher.end();
        }

        // Add remaining content
        result.append(content.substring(lastEnd));

        String unwrapped = result.toString();
        return unwrapped.isEmpty() ? content : unwrapped;
    }

    /**
     * Normalizes block content by removing extra blank lines at edges.
     */
    private String normalizeBlockContent(String content) {
        if (content == null) {
            return ""; //$NON-NLS-1$
        }

        // Remove single leading/trailing newline (but preserve intentional whitespace)
        String result = content;
        if (result.startsWith("\n")) { //$NON-NLS-1$
            result = result.substring(1);
        }
        if (result.endsWith("\n")) { //$NON-NLS-1$
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }
}
