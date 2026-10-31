/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Рабочая область начальной страницы конфигурации: разбор параметров и рендер файла
 * {@code src/Configuration/HomePageWorkArea.hpwa} в формате модели EDT (пакет {@code hpwa}).
 *
 * <p>Файл живёт отдельно от {@code Configuration.mdo} и подхватывается EDT по пути. Ни один
 * инструмент codepilot его не создавал: {@code update_metadata} держит {@code homePageWorkArea}
 * только на чтение, а {@code write_file} и {@code workspace_copy_transform} по замыслу не
 * создают файлы с этим расширением. Класс намеренно чистый (без Eclipse), чтобы контракт
 * формата проверялся юнит-тестом без EDT.</p>
 *
 * <p>Формат снят с шести проектов EDT 2025.2.3: корень {@code hpwa:HomePageWorkArea},
 * элемент {@code workingAreaTemplate} с литералом перечисления
 * {@code com._1c.g5.v8.dt.hpwa.model.WorkingAreaTemplate}, затем колонки {@code columns}
 * (первая - левая, вторая - правая; пустая колонка пишется как {@code <columns/>}), внутри
 * колонки элементы {@code items} с полным именем формы, высотой и видимостью.</p>
 */
public final class HomePageWorkAreaXml {

    public static final String NAMESPACE = "http://g5.1c.ru/v8/dt/hpwa"; //$NON-NLS-1$
    public static final String RELATIVE_PATH = "src/Configuration/HomePageWorkArea.hpwa"; //$NON-NLS-1$
    public static final String TEMPLATE_ONE_COLUMN = "OneColumn"; //$NON-NLS-1$
    public static final String TEMPLATE_TWO_COLUMNS_EQUAL_WIDTH = "TwoColumnsEqualWidth"; //$NON-NLS-1$
    public static final String TEMPLATE_TWO_COLUMNS_VARIABLE_WIDTH = "TwoColumnsVariableWidth"; //$NON-NLS-1$
    public static final String DEFAULT_TEMPLATE = TEMPLATE_TWO_COLUMNS_EQUAL_WIDTH;
    public static final int DEFAULT_HEIGHT = 10;
    public static final List<String> TEMPLATES = List.of(
            TEMPLATE_ONE_COLUMN, TEMPLATE_TWO_COLUMNS_EQUAL_WIDTH, TEMPLATE_TWO_COLUMNS_VARIABLE_WIDTH);

    /**
     * Элемент колонки: полное имя формы ({@code Catalog.X.Form.Y} или {@code CommonForm.X}),
     * высота в строках и видимость по умолчанию.
     */
    public record Item(String formFqn, int height, boolean visible) {
    }

    private HomePageWorkAreaXml() {
    }

    /**
     * Приводит имя шаблона к литералу модели: принимает литерал в любом регистре и имена
     * констант перечисления ({@code TWO_COLUMNS_EQUAL_WIDTH}); пустое значение - шаблон по умолчанию.
     */
    public static String normalizeTemplate(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TEMPLATE;
        }
        String key = raw.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String template : TEMPLATES) {
            if (template.toLowerCase(Locale.ROOT).equals(key)) {
                return template;
            }
        }
        throw new IllegalArgumentException("Unknown working area template: " + raw //$NON-NLS-1$
                + ". Allowed: " + String.join(", ", TEMPLATES)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Форма верхнего уровня BM: {@code <Kind>.<Name>.Form.<FormName>} либо {@code CommonForm.<Name>}. */
    public static boolean looksLikeFormFqn(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return false;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length == 2) {
            return "CommonForm".equals(parts[0]) && !parts[1].isBlank(); //$NON-NLS-1$
        }
        return parts.length == 4 && "Form".equals(parts[2]) //$NON-NLS-1$
                && !parts[0].isBlank() && !parts[1].isBlank() && !parts[3].isBlank();
    }

    /**
     * Проверяет раскладку до обращения к BM: хотя бы одна форма, у одноколоночного шаблона
     * правой колонки нет, каждая форма похожа на FQN формы, высота положительна.
     */
    public static void validate(String template, List<Item> left, List<Item> right) {
        String normalized = normalizeTemplate(template);
        List<Item> leftItems = left != null ? left : List.of();
        List<Item> rightItems = right != null ? right : List.of();
        if (leftItems.isEmpty() && rightItems.isEmpty()) {
            throw new IllegalArgumentException("Home page work area needs at least one form"); //$NON-NLS-1$
        }
        if (TEMPLATE_ONE_COLUMN.equals(normalized) && !rightItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Template OneColumn has no right column: put all forms into left"); //$NON-NLS-1$
        }
        List<Item> all = new ArrayList<>(leftItems);
        all.addAll(rightItems);
        for (Item item : all) {
            if (item == null || !looksLikeFormFqn(item.formFqn())) {
                throw new IllegalArgumentException("Not a form FQN: " + (item == null ? "null" : item.formFqn()) //$NON-NLS-1$ //$NON-NLS-2$
                        + " (expected <Kind>.<Name>.Form.<FormName> or CommonForm.<Name>)"); //$NON-NLS-1$
            }
            if (item.height() <= 0) {
                throw new IllegalArgumentException("Height must be positive for " + item.formFqn()); //$NON-NLS-1$
            }
        }
    }

    /** Рендер файла .hpwa; переводы строк LF, отступ два пробела - как пишет сама EDT. */
    public static String render(String template, List<Item> left, List<Item> right) {
        String normalized = normalizeTemplate(template);
        validate(normalized, left, right);
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
        sb.append("<hpwa:HomePageWorkArea xmlns:hpwa=\"").append(NAMESPACE).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("  <workingAreaTemplate>").append(normalized).append("</workingAreaTemplate>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        appendColumn(sb, left);
        if (!TEMPLATE_ONE_COLUMN.equals(normalized)) {
            appendColumn(sb, right);
        }
        sb.append("</hpwa:HomePageWorkArea>\n"); //$NON-NLS-1$
        return sb.toString();
    }

    private static void appendColumn(StringBuilder sb, List<Item> items) {
        if (items == null || items.isEmpty()) {
            sb.append("  <columns/>\n"); //$NON-NLS-1$
            return;
        }
        sb.append("  <columns>\n"); //$NON-NLS-1$
        for (Item item : items) {
            sb.append("    <items>\n"); //$NON-NLS-1$
            sb.append("      <form>").append(escape(item.formFqn())).append("</form>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("      <height>").append(item.height()).append("</height>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("      <visibility>\n"); //$NON-NLS-1$
            sb.append("        <common>").append(item.visible()).append("</common>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("      </visibility>\n"); //$NON-NLS-1$
            sb.append("    </items>\n"); //$NON-NLS-1$
        }
        sb.append("  </columns>\n"); //$NON-NLS-1$
    }

    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
            case '&' -> sb.append("&amp;"); //$NON-NLS-1$
            case '<' -> sb.append("&lt;"); //$NON-NLS-1$
            case '>' -> sb.append("&gt;"); //$NON-NLS-1$
            case '"' -> sb.append("&quot;"); //$NON-NLS-1$
            default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
