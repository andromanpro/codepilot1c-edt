/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.markdown;

import java.util.Arrays;

import com.codepilot1c.core.logging.VibeLogger;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

/**
 * Markdown парсер на основе flexmark-java.
 *
 * Использует AST-based подход, который:
 * - Парсит markdown в дерево узлов
 * - Обрабатывает каждый тип узла отдельно
 * - Блоки кода сохраняются как есть, без плейсхолдеров
 * - HTML рендерится напрямую из AST
 *
 * Это решает проблему с плейсхолдерами в SimpleMarkdownParser,
 * где плейсхолдеры могли быть модифицированы другими шагами обработки markdown.
 *
 * Поддерживает:
 * - Заголовки (# ## ###)
 * - Жирный и курсив (**bold** *italic*)
 * - Зачеркивание (~~strikethrough~~)
 * - Inline код (`code`)
 * - Блоки кода (```lang)
 * - Таблицы GFM (| col | col |)
 * - Списки (- item, 1. item)
 * - Ссылки [text](url) и автоссылки
 * - Цитаты (> quote)
 * - Горизонтальные линии (---)
 */
public class FlexmarkParser {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(FlexmarkParser.class);

    private final Parser parser;
    private final HtmlRenderer renderer;

    /**
     * Создает новый экземпляр FlexmarkParser с настройками по умолчанию.
     */
    public FlexmarkParser() {
        this(false);
    }

    /**
     * Создает новый экземпляр FlexmarkParser.
     *
     * @param suppressHtml suppress raw HTML from markdown input
     */
    public FlexmarkParser(boolean suppressHtml) {
        MutableDataSet options = new MutableDataSet();

        // Включить расширения для GFM-совместимости
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                AutolinkExtension.create(),
                StrikethroughExtension.create()
        ));

        // Настройки рендеринга
        options.set(HtmlRenderer.SOFT_BREAK, "<br />\n"); //$NON-NLS-1$
        if (suppressHtml) {
            options.set(HtmlRenderer.SUPPRESS_HTML, true);
            options.set(HtmlRenderer.SUPPRESS_HTML_BLOCKS, true);
            options.set(HtmlRenderer.SUPPRESS_HTML_COMMENT_BLOCKS, true);
            options.set(HtmlRenderer.SUPPRESS_INLINE_HTML, true);
            options.set(HtmlRenderer.SUPPRESS_INLINE_HTML_COMMENTS, true);
        }

        // Настройки таблиц
        options.set(TablesExtension.COLUMN_SPANS, false);
        options.set(TablesExtension.APPEND_MISSING_COLUMNS, true);
        options.set(TablesExtension.DISCARD_EXTRA_COLUMNS, true);
        options.set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true);

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options)
                .nodeRendererFactory(new CodeBlockNodeRenderer.Factory())
                .build();

        LOG.debug("FlexmarkParser initialized with extensions: Tables, Autolink, Strikethrough"); //$NON-NLS-1$
    }

    /**
     * Конвертирует Markdown в HTML.
     *
     * @param markdown исходный текст в формате Markdown
     * @return HTML-разметка
     */
    public String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return ""; //$NON-NLS-1$
        }

        try {
            // Нормализация переносов строк
            String normalized = markdown.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

            // Парсинг в AST
            Node document = parser.parse(normalized);
            if (document == null) {
                LOG.warn("FlexmarkParser: parser.parse() returned null"); //$NON-NLS-1$
                return escapeHtml(markdown);
            }

            // Рендеринг в HTML
            String html = renderer.render(document);

            LOG.debug("FlexmarkParser: rendered %d chars markdown to %d chars HTML", //$NON-NLS-1$
                    markdown.length(), html.length());

            return html;
        } catch (Exception e) {
            LOG.error("FlexmarkParser: failed to parse markdown", e); //$NON-NLS-1$
            // Fallback: экранировать как plain text
            return escapeHtml(markdown);
        }
    }

    /**
     * Экранирует HTML-спецсимволы для fallback режима.
     */
    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(">", "&gt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "&quot;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("'", "&#39;"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
